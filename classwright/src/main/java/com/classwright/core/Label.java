package com.classwright.core;

import java.util.ArrayList;
import java.util.List;

/**
 * A position in a method's bytecode that something jumps to.
 *
 * <p>Labels exist because a forward jump has to be emitted before its destination is known. The
 * jump reserves two bytes for its offset, registers itself here, and those bytes are filled in when
 * the label is finally bound.
 *
 * <p>A label also accumulates the {@link StackFrame} that each incoming path expects to find. Once
 * bound, the merge of those frames is what gets written into the {@code StackMapTable}. A label
 * that nothing ever jumps to needs no frame at all, which is why straight-line generated methods
 * carry no stack map attribute whatsoever.
 *
 * <p>Not part of the public API. Callers use the structured control-flow methods on
 * {@link CodeBuilder} instead, which is what keeps the generated control flow well-formed and the
 * frame bookkeeping correct. See {@link CodeBuilder} for why that restriction is deliberate.
 */
final class Label {

    /**
     * A pending forward reference: where to patch, and what the offset is relative to.
     *
     * @param wide whether the slot is 32-bit. Ordinary jumps use a signed 16-bit offset; the two
     *             switch instructions use 32-bit, which is why they can span a whole method.
     */
    private record Patch(int offsetPosition, int instructionStart, boolean wide) {
    }

    private final List<Patch> patches = new ArrayList<>();

    private int offset = -1;
    private StackFrame expectedFrame;

    /** Whether a stack map entry must be emitted here, i.e. whether anything jumps to it. */
    private boolean needsFrame;

    boolean isBound() {
        return offset >= 0;
    }

    int offset() {
        if (!isBound()) {
            throw new CodeGenerationException("label has not been bound yet");
        }
        return offset;
    }

    StackFrame expectedFrame() {
        return expectedFrame;
    }

    boolean needsFrame() {
        return needsFrame;
    }

    /**
     * Records that a path arrives here with the given verifier state.
     *
     * <p>Called for each incoming jump, and once more when the label is bound if the preceding
     * instruction can fall through into it. Merging as we go means the label always holds the state
     * common to everything that has reached it so far.
     */
    void arriveWith(StackFrame frame) {
        expectedFrame = expectedFrame == null ? frame : expectedFrame.merge(frame);
    }

    /**
     * Marks this label as a branch target, so a stack map entry is emitted when it is bound.
     *
     * <p>Separate from {@link #arriveWith} because falling through into a label is not the same as
     * jumping to it: fall-through needs no frame, but a jump does, even a backward one whose target
     * has already been bound.
     */
    void markAsBranchTarget() {
        needsFrame = true;
    }

    /** Registers a 16-bit forward reference to be filled in at bind time. */
    void addPatch(int offsetPosition, int instructionStart) {
        patches.add(new Patch(offsetPosition, instructionStart, false));
    }

    /** Registers a 32-bit forward reference, as used by the switch instructions. */
    void addWidePatch(int offsetPosition, int instructionStart) {
        patches.add(new Patch(offsetPosition, instructionStart, true));
    }

    /**
     * Fixes this label at {@code position} and resolves every forward reference to it.
     *
     * <p>Branch offsets are signed 16-bit and relative to the start of the branch instruction, not
     * to the offset field within it &mdash; an easy thing to get wrong by three bytes.
     */
    void bindAt(int position, ByteWriter code) {
        if (isBound()) {
            throw new CodeGenerationException("label bound twice");
        }
        offset = position;
        for (Patch patch : patches) {
            int relative = position - patch.instructionStart();
            if (patch.wide()) {
                code.patchU4(patch.offsetPosition(), relative);
            } else {
                if (relative < Short.MIN_VALUE || relative > Short.MAX_VALUE) {
                    throw new CodeGenerationException("branch distance of " + relative
                            + " bytes exceeds the 16-bit range of a jump instruction. The generated "
                            + "method is too large; split it into several smaller ones.");
                }
                code.patchU2(patch.offsetPosition(), relative & 0xFFFF);
            }
        }
        patches.clear();
    }

    @Override
    public String toString() {
        return isBound() ? "L" + offset : "L?";
    }
}

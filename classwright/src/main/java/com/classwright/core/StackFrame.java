package com.classwright.core;

import java.util.ArrayList;
import java.util.List;

/**
 * An immutable snapshot of the verifier's state: what is in each local variable slot, and what is
 * on the operand stack.
 *
 * <p>Snapshots are taken at every jump and every label. The one taken at a jump records what the
 * target must expect; the one taken at a label records what actually arrives. Where several paths
 * reach the same label the snapshots are merged, and the result becomes a {@code StackMapTable}
 * entry.
 *
 * <p>This is the whole trick that makes a from-scratch bytecode engine tractable. A tool that
 * <em>reads</em> arbitrary bytecode has to recover this information by dataflow analysis, which is
 * genuinely difficult. A tool that <em>writes</em> bytecode already knows it: the generator put
 * every value on the stack and knows what each one was. Recording it is bookkeeping, not inference.
 *
 * <h2>Representation</h2>
 *
 * <p>Locals are slot-indexed, so a {@code long} in slot 3 stores {@link VerificationType#LONG} at
 * index 3 and {@link VerificationType#TOP} at index 4. That mirrors how the JVM addresses locals
 * and keeps slot arithmetic honest.
 *
 * <p>The stack is entry-indexed, so a {@code long} is a single element. That mirrors how
 * {@code StackMapTable} encodes it. The two differ, which is confusing exactly once.
 */
final class StackFrame {

    private final List<VerificationType> locals;
    private final List<VerificationType> stack;

    private StackFrame(List<VerificationType> locals, List<VerificationType> stack) {
        this.locals = List.copyOf(locals);
        this.stack = List.copyOf(stack);
    }

    static StackFrame of(List<VerificationType> locals, List<VerificationType> stack) {
        return new StackFrame(locals, stack);
    }

    List<VerificationType> locals() {
        return locals;
    }

    List<VerificationType> stack() {
        return stack;
    }

    /**
     * Combines this frame with one arriving from another path.
     *
     * <p>The operand stacks must be the same depth. Anything else means one path left a value
     * behind that the other did not, which no amount of type merging can reconcile &mdash; and
     * which is always a generator bug rather than something to paper over.
     *
     * <p>Local variable arrays may differ in length: a slot written on one path and not the other
     * simply is not guaranteed to hold anything after the join, so the shorter array wins and any
     * extra slots are dropped.
     *
     * @param other the frame from the other path
     * @return the merged frame
     * @throws CodeGenerationException if the two are irreconcilable
     */
    StackFrame merge(StackFrame other) {
        if (stack.size() != other.stack.size()) {
            throw new CodeGenerationException(
                    "control-flow paths reach the same point with different operand stack depths ("
                            + describeStack() + " versus " + other.describeStack()
                            + "). Every branch of a conditional must leave the stack the same.");
        }

        List<VerificationType> mergedStack = new ArrayList<>(stack.size());
        for (int i = 0; i < stack.size(); i++) {
            mergedStack.add(stack.get(i).merge(other.stack.get(i)));
        }

        int commonLocals = Math.min(locals.size(), other.locals.size());
        List<VerificationType> mergedLocals = new ArrayList<>(commonLocals);
        for (int i = 0; i < commonLocals; i++) {
            mergedLocals.add(locals.get(i).merge(other.locals.get(i)));
        }

        return new StackFrame(mergedLocals, mergedStack);
    }

    /**
     * Writes this as a {@code full_frame} entry (JVMS 4.7.4).
     *
     * <p>Only the full form is emitted. The format also defines six compressed forms that express a
     * frame as a delta from the previous one, and they would shrink the attribute by a useful
     * fraction &mdash; but each has its own edge cases around which locals changed and how deep the
     * stack is, and a subtly wrong compressed frame produces a {@link VerifyError} that points at
     * the bytecode rather than at the frame. The full form is always legal and always says exactly
     * what it means. Compression is a size optimisation for later, measured rather than assumed.
     *
     * @param out          destination
     * @param pool         constant pool, for object type references
     * @param offsetDelta  distance from the previous frame, per the format's encoding
     */
    void writeTo(ByteWriter out, ConstantPool pool, int offsetDelta) {
        out.u1(255);                        // full_frame
        out.u2(offsetDelta);

        List<VerificationType> trimmed = trimTrailingUnused();
        int declaredLocals = 0;
        for (int i = 0; i < trimmed.size(); i++) {
            declaredLocals++;
            if (trimmed.get(i).isWide()) {
                // The following slot is the wide value's second half, implied rather than
                // declared. If it holds anything else the simulation is corrupt, and emitting the
                // frame anyway would silently drop that entry and produce a VerifyError at class
                // load; failing here keeps the error at generation time, where it can be traced.
                if (i + 1 < trimmed.size()
                        && trimmed.get(i + 1) != VerificationType.TOP) {
                    throw new CodeGenerationException("the slot after a " + trimmed.get(i)
                            + " local holds " + trimmed.get(i + 1) + " instead of the wide "
                            + "value's second half; a store overwrote half of a long or double");
                }
                i++;
            }
        }
        out.u2(declaredLocals);
        for (int i = 0; i < trimmed.size(); i++) {
            VerificationType local = trimmed.get(i);
            local.writeTo(out, pool);
            if (local.isWide()) {
                i++;
            }
        }

        out.u2(stack.size());
        for (VerificationType entry : stack) {
            entry.writeTo(out, pool);
        }
    }

    /**
     * Drops trailing dead slots.
     *
     * <p>Slots past the last live local carry no information, and declaring them inflates every
     * frame in a method that allocates scratch variables inside a branch. Slots in the
     * <em>middle</em> are kept: a gap is meaningful, since the locals list is positional.
     */
    private List<VerificationType> trimTrailingUnused() {
        int end = locals.size();
        while (end > 0 && locals.get(end - 1).kind() == VerificationType.Kind.TOP) {
            end--;
        }
        return locals.subList(0, end);
    }

    private String describeStack() {
        return stack.isEmpty() ? "empty stack" : "stack " + stack;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StackFrame that
                && locals.equals(that.locals) && stack.equals(that.stack);
    }

    @Override
    public int hashCode() {
        return 31 * locals.hashCode() + stack.hashCode();
    }

    @Override
    public String toString() {
        return "locals=" + locals + " stack=" + stack;
    }
}

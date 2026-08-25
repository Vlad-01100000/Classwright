/**
 * The bytecode engine: turns a description of a class into a class file.
 *
 * <p>Start at {@link com.classwright.core.CwClassWriter}. It produces
 * {@link com.classwright.core.MethodBuilder}s, which produce
 * {@link com.classwright.core.CodeBuilder}s, which emit instructions.
 *
 * <h2>Write-only, by design</h2>
 *
 * <p>There is no class-file reader in this package, and adding one would undo the main reason the
 * library exists. Everything Classwright needs to know about an existing class it learns from core
 * reflection. A parser, by contrast, has to be taught every new class-file format version, which
 * couples the library's release schedule to the JDK's &mdash; and a parser that falls behind is
 * exactly how CGLib stopped working on Java 17.
 *
 * <h2>The engine checks itself as it goes</h2>
 *
 * <p>{@link com.classwright.core.CodeBuilder} maintains the verifier's view of the operand stack
 * and local variables continuously, so a type error is reported at the generator call that caused
 * it rather than surfacing later as a {@link java.lang.VerifyError} naming a bytecode offset. The
 * same bookkeeping yields {@code max_stack}, {@code max_locals}, and the
 * {@code StackMapTable} as by-products.
 *
 * <p>Stack map frames are the reason a from-scratch bytecode engine is usually considered hard.
 * Since Java 7 the verifier requires one at every branch target and will not infer them, so a tool
 * that reads arbitrary bytecode must recover them by dataflow analysis. A tool that <em>writes</em>
 * bytecode already knows the answer: it put every value there. Combined with the restriction that
 * control flow is expressed through structured regions rather than raw jumps, computing frames
 * becomes bookkeeping rather than inference.
 *
 * <h2>Emitted class-file version</h2>
 *
 * <p>Java 8 (major 52) by default, deliberately far below the Java 17 baseline. JVMs accept old
 * class files essentially forever, so emitting low maximises the range of runtimes &mdash;
 * including future ones &mdash; that will load the output. See
 * {@link com.classwright.core.ClassFileVersion}.
 */
package com.classwright.core;

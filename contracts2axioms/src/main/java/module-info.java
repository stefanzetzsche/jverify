/**
 * contracts2axioms — lift {@code @AsProperty} contracts (validated by
 * jqwik via {@code contracts2jqwik}) into the {@code @Contract} axiom
 * form the JVerify verifier consumes.
 *
 * <p>Run via {@link org.strata.jverify.contracts2axioms.Main}. Pass an
 * {@code @AsProperty} contract file in (e.g.
 * {@code contracts/jverify/specs/java/lang/IntegerContract.java}) and a
 * declaration of which JDK type it describes (the verifier needs a
 * {@code @Contract(java.lang.Integer.class)} annotation on the output);
 * the tool emits a {@code @Pure} / {@code throw new ContractException()}
 * axiom file ready for the verifier's {@code --contract-path}.</p>
 *
 * <p>The translation is mechanical and intentionally a strict superset of
 * a hand-written lift:</p>
 * <ul>
 *     <li>Drop {@code @AsProperty} on each method, add {@code @Pure}.</li>
 *     <li>Drop jqwik {@code @ForAll} (and any {@code @IntRange} /
 *         {@code @LongRange}) parameter annotations.</li>
 *     <li>Replace each method body with {@code throw new ContractException();}.</li>
 *     <li>Annotate the class with {@code @Contract(<JdkType>.class)}.</li>
 *     <li>Drop jqwik / jverify-property imports; add the verifier's
 *         {@code Contract} / {@code ContractException} / {@code Pure} imports.</li>
 *     <li>Rewrite postcondition lambda parameters from boxed types
 *         ({@code Integer r}) to primitive types ({@code int r}) where the
 *         original delegating call returns a primitive — the verifier's
 *         current type translator only supports primitives, and an unboxed
 *         lambda parameter avoids triggering an unsupported {@code intValue()}
 *         resolution.</li>
 *     <li>{@code precondition} / {@code postcondition} clauses are copied
 *         verbatim. They were already validated by jqwik against the real
 *         JDK, so we trust their content.</li>
 * </ul>
 *
 * <p>The output file is plain Java, intended to be reviewed and committed
 * alongside the input. There is no annotation processing, no bytecode
 * rewriting — same model as {@code contracts2jqwik}, opposite direction.</p>
 */
module org.strata.jverify.contracts2axioms {
    requires org.strata.jverify;
    requires org.strata.jverify.common;
    requires com.github.javaparser.core;

    exports org.strata.jverify.contracts2axioms;
}

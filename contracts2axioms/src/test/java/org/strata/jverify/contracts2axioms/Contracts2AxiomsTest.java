package org.strata.jverify.contracts2axioms;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests for the lift from {@code @AsProperty} contracts to {@code @Contract}
 * axioms.
 */
class Contracts2AxiomsTest {

    private static String translate(String source, String jdkType) {
        Contracts2Axioms.Result r = Contracts2Axioms.translate(source, "Test.java", jdkType);
        assertNotNull(r.output, "expected non-null output for source:\n" + source
            + "\nwarnings: " + r.warnings);
        return r.output;
    }

    @Test
    void simpleLift() {
        String source = """
            import org.strata.jverify.AsProperty;
            import net.jqwik.api.ForAll;
            import static org.strata.jverify.JVerify.*;
            class IntegerContract {
                @AsProperty
                public static int sum(@ForAll int a, @ForAll int b) {
                    postcondition((Integer r) -> r == a + b);
                    return java.lang.Integer.sum(a, b);
                }
            }
            """;
        String out = translate(source, "java.lang.Integer");

        assertTrue(out.contains("@Contract(java.lang.Integer.class)"),
            "expected @Contract on the class, got:\n" + out);
        assertTrue(out.contains("@Pure"), "expected @Pure on the method");
        assertFalse(out.contains("@AsProperty"), "expected @AsProperty stripped");
        assertFalse(out.contains("@ForAll"), "expected @ForAll stripped");
        assertTrue(out.contains("throw new ContractException()"),
            "expected ContractException body");
        assertFalse(out.contains("java.lang.Integer.sum(a, b)"),
            "expected delegating call removed from body, got:\n" + out);
        assertTrue(out.contains("postcondition((int r) -> r == a + b)"),
            "expected primitive lambda parameter (int) and verbatim postcondition body, got:\n" + out);
    }

    @Test
    void preconditionsAreCopiedVerbatim() {
        String source = """
            import org.strata.jverify.AsProperty;
            import net.jqwik.api.ForAll;
            import static org.strata.jverify.JVerify.*;
            class IntegerContract {
                @AsProperty
                public static int divide(@ForAll int a, @ForAll int b) {
                    precondition(b != 0);
                    postcondition((int r) -> r * b == a);
                    return java.lang.Integer.divideExact(a, b);
                }
            }
            """;
        String out = translate(source, "java.lang.Integer");
        assertTrue(out.contains("precondition(b != 0)"),
            "expected precondition copied verbatim, got:\n" + out);
        // Also tests that the @AsProperty method named `divide` becomes
        // `divideExact` — the JDK method name extracted from the body.
        assertTrue(out.contains("divideExact"),
            "expected method renamed to JDK name (divideExact), got:\n" + out);
    }

    @Test
    void overloadSuffixIsStripped() {
        // The @AsProperty form uses unique Java names like `valueOfString`
        // to disambiguate overloads. The lift should rename to the JDK name
        // (`valueOf`), since the verifier looks up by simple JDK name.
        String source = """
            import org.strata.jverify.AsProperty;
            import net.jqwik.api.ForAll;
            import static org.strata.jverify.JVerify.*;
            class BooleanContract {
                @AsProperty
                public static java.lang.Boolean valueOfString(@ForAll String s) {
                    postcondition((java.lang.Boolean r) -> true);
                    return java.lang.Boolean.valueOf(s);
                }
            }
            """;
        String out = translate(source, "java.lang.Boolean");
        assertTrue(out.contains("public static java.lang.Boolean valueOf(String s)"),
            "expected method renamed to valueOf, got:\n" + out);
        assertFalse(out.contains("valueOfString"),
            "expected validation-form name removed");
    }

    @Test
    void boxedLambdaParamRewrittenForIntReturn() {
        String source = """
            import org.strata.jverify.AsProperty;
            import net.jqwik.api.ForAll;
            import static org.strata.jverify.JVerify.*;
            class MathContract {
                @AsProperty
                public static int max(@ForAll int a, @ForAll int b) {
                    postcondition((Integer r) -> r >= a);
                    return java.lang.Math.max(a, b);
                }
            }
            """;
        String out = translate(source, "java.lang.Math");
        assertTrue(out.contains("(int r) ->"),
            "expected boxed Integer rewritten to int, got:\n" + out);
        assertFalse(out.contains("(Integer r) ->"),
            "expected no boxed lambda param remaining");
    }

    @Test
    void boxedLambdaParamPreservedForLongReturn() {
        // JVerify's postcondition API has IntPredicate and BooleanPredicate
        // overloads but no LongPredicate — a `(long r) -> ...` lambda would
        // not resolve. Keep the boxed Long for non-int/boolean primitives.
        String source = """
            import org.strata.jverify.AsProperty;
            import net.jqwik.api.ForAll;
            import static org.strata.jverify.JVerify.*;
            class MathContract {
                @AsProperty
                public static long maxLong(@ForAll long a, @ForAll long b) {
                    postcondition((Long r) -> r >= a);
                    return java.lang.Math.max(a, b);
                }
            }
            """;
        String out = translate(source, "java.lang.Math");
        assertTrue(out.contains("(Long r) ->"),
            "expected Long lambda parameter preserved (no LongPredicate overload), got:\n" + out);
    }

    @Test
    void noAsPropertyMethodsReturnsNullOutput() {
        String source = """
            class C {
                public static int plain(int x) { return x + 1; }
            }
            """;
        Contracts2Axioms.Result r = Contracts2Axioms.translate(source, "Test.java", "java.lang.Object");
        assertNull(r.output, "expected null output when no @AsProperty methods present");
    }

    @Test
    void axiomFileSitsInBuiltinPackage() {
        String source = """
            import org.strata.jverify.AsProperty;
            import net.jqwik.api.ForAll;
            import static org.strata.jverify.JVerify.*;
            class IntegerContract {
                @AsProperty
                public static int sum(@ForAll int a, @ForAll int b) {
                    postcondition((int r) -> r == a + b);
                    return java.lang.Integer.sum(a, b);
                }
            }
            """;
        String out = translate(source, "java.lang.Integer");
        assertTrue(out.startsWith("package org.strata.jverify.builtin;"),
            "expected output package to be org.strata.jverify.builtin (matching upstream "
            + "builtin-contracts/), got:\n" + out);
    }
}

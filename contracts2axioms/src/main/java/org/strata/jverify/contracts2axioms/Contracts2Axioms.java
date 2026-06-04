package org.strata.jverify.contracts2axioms;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Lift an {@code @AsProperty} contract file into a {@code @Contract} axiom
 * file consumable by JVerify's verifier via {@code --contract-path}.
 *
 * <p>The transformation is mechanical, mirroring how {@code contracts2jqwik}
 * goes the other way. Postconditions are copied verbatim — they were
 * already validated by jqwik against the real JDK behaviour.</p>
 *
 * <p>This translator's only job is to swap the surrounding form. See the
 * module-info Javadoc for the exact rules.</p>
 */
public final class Contracts2Axioms {

    private static final String AS_PROPERTY_SIMPLE = "AsProperty";
    private static final String AS_PROPERTY_FQN = "org.strata.jverify.AsProperty";

    /** Result of translating a single input file. */
    public static final class Result {
        /** The generated axiom Java source, or null if there was nothing to emit. */
        public final String output;
        /** A reasonable filename for the generated source. */
        public final String suggestedFileName;
        /** Soft warnings about clauses or methods that could not be lifted. */
        public final List<String> warnings;

        Result(String output, String suggestedFileName, List<String> warnings) {
            this.output = output;
            this.suggestedFileName = suggestedFileName;
            this.warnings = warnings;
        }
    }

    private Contracts2Axioms() {}

    /**
     * Translate the contents of an {@code @AsProperty} contract file into
     * an axiom file describing the JDK type {@code jdkType} (e.g.
     * {@code java.lang.Integer}).
     */
    public static Result translate(String source, String inputFileName, String jdkType) {
        CompilationUnit cu = StaticJavaParser.parse(source);
        List<String> warnings = new ArrayList<>();

        Optional<ClassOrInterfaceDeclaration> originalOpt = cu.getTypes().stream()
            .filter(t -> t instanceof ClassOrInterfaceDeclaration)
            .map(t -> (ClassOrInterfaceDeclaration) t)
            .findFirst();
        if (originalOpt.isEmpty()) {
            return new Result(null, null, warnings);
        }
        ClassOrInterfaceDeclaration original = originalOpt.get();

        List<MethodDeclaration> asPropertyMethods = original.getMethods().stream()
            .filter(Contracts2Axioms::hasAsProperty)
            .toList();
        if (asPropertyMethods.isEmpty()) {
            return new Result(null, null, warnings);
        }

        // Build the output compilation unit.
        CompilationUnit out = new CompilationUnit();
        // Axiom files live in `org.strata.jverify.builtin` to match how the
        // upstream `builtin-contracts/` module is laid out and how the verifier
        // discovers them on the contract path.
        out.setPackageDeclaration("org.strata.jverify.builtin");

        // Imports the verifier expects.
        out.addImport("org.strata.jverify.Contract");
        out.addImport("org.strata.jverify.ContractException");
        out.addImport("org.strata.jverify.Pure");
        out.addImport("org.strata.jverify.JVerify", true, true);  // static, on-demand

        ClassOrInterfaceDeclaration generated = out.addClass(original.getNameAsString());
        generated.addOrphanComment(new com.github.javaparser.ast.comments.LineComment(
            " Lifted from " + inputFileName + " by contracts2axioms."));
        generated.addOrphanComment(new com.github.javaparser.ast.comments.LineComment(
            " Postconditions are copied verbatim from the validated form."));

        // @Contract(<jdkType>.class) on the class.
        ClassOrInterfaceType jdkTypeRef = new ClassOrInterfaceType(null, jdkType);
        generated.addAnnotation(new SingleMemberAnnotationExpr(
            new com.github.javaparser.ast.expr.Name("Contract"),
            new ClassExpr(jdkTypeRef)));

        for (MethodDeclaration original_ : asPropertyMethods) {
            MethodDeclaration lifted = liftMethod(original_, warnings);
            if (lifted != null) {
                generated.addMember(lifted);
            }
        }

        if (generated.getMembers().isEmpty()) {
            warnings.add("no liftable methods in " + inputFileName);
            return new Result(null, original.getNameAsString() + ".java", warnings);
        }

        return new Result(out.toString(), original.getNameAsString() + ".java", warnings);
    }

    /**
     * Lift a single {@code @AsProperty} method to the {@code @Pure} axiom form.
     * Returns null if the method has no body to lift from.
     */
    private static MethodDeclaration liftMethod(MethodDeclaration original, List<String> warnings) {
        if (original.getBody().isEmpty()) {
            warnings.add("method '" + original.getNameAsString() + "' has no body");
            return null;
        }

        MethodDeclaration lifted = original.clone();

        // Rename overload-suffixed @AsProperty method names back to their
        // canonical JDK names. The validation form needs unique Java method
        // names to disambiguate overloads (e.g. valueOf(boolean) vs
        // valueOf(String)) and so the worker prompt produces names like
        // `valueOf` and `valueOfString`. The verifier looks up axioms by
        // simple JDK method name, so we strip the suffix.
        //
        // The strategy: read from the delegating call in the original body.
        // The body is `return java.lang.X.<jdkMethod>(args);` so the
        // <jdkMethod> name is the canonical name we want.
        String jdkName = extractDelegatedMethodName(original);
        if (jdkName != null && !jdkName.equals(lifted.getNameAsString())) {
            lifted.setName(jdkName);
        }

        // Replace @AsProperty with @Pure on the method.
        NodeList<AnnotationExpr> newAnnotations = new NodeList<>();
        boolean wasAsProperty = false;
        for (AnnotationExpr ann : lifted.getAnnotations()) {
            String name = ann.getNameAsString();
            if (name.equals(AS_PROPERTY_SIMPLE) || name.equals(AS_PROPERTY_FQN)) {
                wasAsProperty = true;
                continue;
            }
            newAnnotations.add(ann);
        }
        if (wasAsProperty) {
            newAnnotations.add(new MarkerAnnotationExpr("Pure"));
        }
        lifted.setAnnotations(newAnnotations);

        // Strip jqwik annotations from each parameter.
        for (Parameter p : lifted.getParameters()) {
            NodeList<AnnotationExpr> kept = new NodeList<>();
            for (AnnotationExpr ann : p.getAnnotations()) {
                if (isJqwikAnnotation(ann)) continue;
                kept.add(ann);
            }
            p.setAnnotations(kept);
        }

        // Replace the body with `throw new ContractException();`. The
        // verifier never executes the body — the contract clauses are the spec.
        // First, preserve the precondition / postcondition statements at the
        // top of the body (everything before the delegating call), then drop
        // the rest and append the throw.
        BlockStmt origBody = original.getBody().orElseThrow();
        BlockStmt newBody = new BlockStmt();
        for (var stmt : origBody.getStatements()) {
            if (isContractClause(stmt)) {
                var rewritten = rewriteBoxedLambdaParam(stmt.clone(), lifted.getType());
                newBody.addStatement(rewritten);
            }
        }
        newBody.addStatement(new ThrowStmt(new ObjectCreationExpr(
            null,
            new ClassOrInterfaceType(null, "ContractException"),
            new NodeList<>())));
        lifted.setBody(newBody);

        return lifted;
    }

    /**
     * If the lifted method's return type is a primitive (e.g. {@code int}),
     * the validation form may have used the boxed type ({@code Integer r}) as
     * the postcondition lambda parameter. The verifier's type translator only
     * handles primitives — a boxed parameter triggers an unsupported
     * {@code intValue()} resolution against the contracted type. Rewrite
     * boxed lambda parameters to their primitive equivalents when the return
     * type allows it.
     *
     * <p>This is a best-effort rewrite: only single-parameter lambdas with a
     * recognised box → primitive mapping are touched. Anything else passes
     * through unchanged.</p>
     */
    private static com.github.javaparser.ast.stmt.Statement rewriteBoxedLambdaParam(
            com.github.javaparser.ast.stmt.Statement stmt, Type returnType) {
        if (!(stmt instanceof com.github.javaparser.ast.stmt.ExpressionStmt expr)) return stmt;
        if (!(expr.getExpression() instanceof com.github.javaparser.ast.expr.MethodCallExpr call)) return stmt;
        if (!call.getNameAsString().equals("postcondition")) return stmt;
        if (call.getArguments().size() != 1) return stmt;
        if (!(call.getArguments().get(0) instanceof com.github.javaparser.ast.expr.LambdaExpr lambda)) return stmt;
        if (lambda.getParameters().size() != 1) return stmt;

        Parameter param = lambda.getParameters().get(0);
        Type paramType = param.getType();
        if (!(paramType instanceof ClassOrInterfaceType boxedType)) return stmt;

        // Only rewrite when the return type is primitive and the lambda
        // parameter is the matching boxed type.
        if (!(returnType instanceof PrimitiveType primReturn)) return stmt;

        String boxedName = boxedType.getNameAsString();
        String primReturnName = primReturn.asString();
        // Only rewrite for primitive types JVerify has dedicated postcondition
        // overloads for. The current API has IntPredicate and BooleanPredicate
        // only; other primitives (long, byte, short, char, float, double) must
        // keep the boxed lambda parameter so postcondition resolves to the
        // generic Predicate<T> overload.
        boolean matches = switch (primReturnName) {
            case "int"     -> boxedName.equals("Integer") || boxedName.equals("java.lang.Integer");
            case "boolean" -> boxedName.equals("Boolean") || boxedName.equals("java.lang.Boolean");
            default -> false;
        };
        if (!matches) return stmt;

        param.setType(primReturn.clone());
        return stmt;
    }

    /**
     * Extract the JDK method name from the delegating body of an
     * {@code @AsProperty} method. The body is conventionally
     * {@code return java.lang.X.method(args);} (or
     * {@code return X.method(args);} after type-name shadowing) — we
     * just want the simple name of the called method.
     *
     * <p>Returns null if no delegating call could be found. Callers fall
     * back to the original method name in that case.</p>
     */
    private static String extractDelegatedMethodName(MethodDeclaration original) {
        var bodyOpt = original.getBody();
        if (bodyOpt.isEmpty()) return null;
        for (var stmt : bodyOpt.get().getStatements()) {
            if (stmt instanceof com.github.javaparser.ast.stmt.ReturnStmt ret
                    && ret.getExpression().isPresent()) {
                var expr = ret.getExpression().get();
                if (expr instanceof com.github.javaparser.ast.expr.MethodCallExpr call) {
                    return call.getNameAsString();
                }
            }
            if (stmt instanceof com.github.javaparser.ast.stmt.ExpressionStmt expr
                    && expr.getExpression() instanceof com.github.javaparser.ast.expr.MethodCallExpr call
                    && !call.getNameAsString().equals("precondition")
                    && !call.getNameAsString().equals("postcondition")) {
                return call.getNameAsString();
            }
        }
        return null;
    }

    /** True for jqwik parameter annotations like {@code @ForAll}, {@code @IntRange}, etc. */
    private static boolean isJqwikAnnotation(AnnotationExpr ann) {
        String name = ann.getNameAsString();
        // Match by simple name; jqwik annotations all live under net.jqwik.*.
        return name.equals("ForAll")
            || name.equals("IntRange")
            || name.equals("LongRange")
            || name.equals("StringLength")
            || name.equals("DoubleRange")
            || name.equals("FloatRange")
            || name.equals("Positive")
            || name.equals("Negative")
            || name.startsWith("net.jqwik.");
    }

    /**
     * True if the statement is a top-level call to {@code precondition(...)}
     * or {@code postcondition(...)}.
     */
    private static boolean isContractClause(com.github.javaparser.ast.stmt.Statement stmt) {
        if (!(stmt instanceof com.github.javaparser.ast.stmt.ExpressionStmt expr)) return false;
        if (!(expr.getExpression() instanceof com.github.javaparser.ast.expr.MethodCallExpr call)) return false;
        String n = call.getNameAsString();
        return n.equals("precondition") || n.equals("postcondition");
    }

    private static boolean hasAsProperty(MethodDeclaration method) {
        for (AnnotationExpr ann : method.getAnnotations()) {
            String name = ann.getNameAsString();
            if (name.equals(AS_PROPERTY_SIMPLE) || name.equals(AS_PROPERTY_FQN)) {
                return true;
            }
        }
        return false;
    }
}

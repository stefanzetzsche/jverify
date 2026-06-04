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
        // Axiom files live in `jverify.builtin.<jdk-package>` to mirror the
        // contracts/ tree on disk and keep humans able to find things by JDK
        // package. The verifier itself only cares about the @Contract
        // annotation, not the on-disk path.
        out.setPackageDeclaration(axiomPackageFor(jdkType));

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

        // Lift each @AsProperty method to the axiom form. If multiple input
        // methods lift to the same (method-name, parameter-types) — for
        // example HexFormat.isHexDigitDigits, isHexDigitUppercase,
        // isHexDigitLowercase all targeting java.util.HexFormat.isHexDigit(char) —
        // dedupe by Java signature. We only merge postcondition clauses when
        // parameter *names* also match (so identifiers in the clauses stay in
        // scope); otherwise we keep the first and drop the rest with a
        // warning, since merging clauses across different parameter names
        // would produce references to identifiers that aren't bound.
        java.util.Map<String, MethodDeclaration> bySig = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> sigToParamSig = new java.util.LinkedHashMap<>();
        for (MethodDeclaration original_ : asPropertyMethods) {
            MethodDeclaration lifted = liftMethod(original_, warnings);
            if (lifted == null) continue;
            String sig = signatureKey(lifted);
            String paramSig = signatureKeyWithParamNames(lifted);
            MethodDeclaration existing = bySig.get(sig);
            if (existing == null) {
                bySig.put(sig, lifted);
                sigToParamSig.put(sig, paramSig);
            } else if (sigToParamSig.get(sig).equals(paramSig)) {
                mergeContractClauses(existing, lifted, warnings);
            } else {
                warnings.add("dropped duplicate axiom for " + sig
                    + " (a previous @AsProperty method targeted the same JDK method "
                    + "with different parameter names; cannot safely merge their clauses)");
            }
        }
        for (MethodDeclaration m : bySig.values()) {
            generated.addMember(m);
        }

        // Carry over private/non-@AsProperty helper methods. The validation
        // form sometimes factors postcondition checks into helper methods
        // (e.g. BitSetContract's `allZeroBytes`). Those helpers aren't
        // contracts themselves but are referenced by postconditions, so the
        // lifted axiom file needs them in scope. We keep them on the same
        // class with their original bodies — the verifier doesn't execute
        // them, but it does need the symbol to resolve.
        for (MethodDeclaration m : original.getMethods()) {
            if (hasAsProperty(m)) continue;
            MethodDeclaration helperCopy = m.clone();
            // Strip jqwik annotations defensively.
            for (Parameter p : helperCopy.getParameters()) {
                NodeList<AnnotationExpr> kept = new NodeList<>();
                for (AnnotationExpr ann : p.getAnnotations()) {
                    if (!isJqwikAnnotation(ann)) kept.add(ann);
                }
                p.setAnnotations(kept);
            }
            generated.addMember(helperCopy);
        }

        if (generated.getMembers().isEmpty()) {
            warnings.add("no liftable methods in " + inputFileName);
            return new Result(null, original.getNameAsString() + ".java", warnings);
        }

        return new Result(out.toString(), original.getNameAsString() + ".java", warnings);
    }

    /**
     * Lift a single {@code @AsProperty} method to the {@code @Pure} axiom form.
     * Returns null if the method has no body to lift from, or if the lift
     * cannot produce a signature that matches a JDK method (e.g. when the
     * validation form uses round-trip-style inputs that don't correspond
     * 1:1 to the JDK method's parameters).
     */
    private static MethodDeclaration liftMethod(MethodDeclaration original, List<String> warnings) {
        if (original.getBody().isEmpty()) {
            warnings.add("method '" + original.getNameAsString() + "' has no body");
            return null;
        }

        // Detect "fake-signature" methods: validation contracts where the
        // @AsProperty parameter list doesn't match the delegating JDK call's
        // argument list. These are usually round-trip / property-style tests
        // (e.g. ByteContract.parseByte(byte b) which calls
        // Byte.parseByte(Byte.toString(b))). The axiom form describes ONE JDK
        // method's signature; if the validation form's parameters don't
        // correspond directly to the JDK method's, the lift would produce an
        // axiom that doesn't match any real JDK method.
        if (delegatedArgsDontMatchParameters(original)) {
            warnings.add("skipping '" + original.getNameAsString()
                + "' — validation parameters don't match the delegating JDK call's "
                + "argument list (likely a round-trip-style test that doesn't lift to one JDK method)");
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
     * A signature key matching Java's overload resolution: simple method name
     * + parameter type names (in order). Used to detect when two different
     * {@code @AsProperty} methods lift to the same JDK method.
     */
    private static String signatureKey(MethodDeclaration m) {
        StringBuilder sb = new StringBuilder(m.getNameAsString()).append('(');
        for (int i = 0; i < m.getParameters().size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(m.getParameters().get(i).getType().asString());
        }
        return sb.append(')').toString();
    }

    /**
     * Stricter signature key including parameter names. Only methods with
     * identical name + types + parameter names should have their postconditions
     * merged; otherwise referenced identifiers may not be in scope after merge.
     */
    private static String signatureKeyWithParamNames(MethodDeclaration m) {
        StringBuilder sb = new StringBuilder(m.getNameAsString()).append('(');
        for (int i = 0; i < m.getParameters().size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(m.getParameters().get(i).getType().asString())
              .append(' ')
              .append(m.getParameters().get(i).getNameAsString());
        }
        return sb.append(')').toString();
    }

    /**
     * Merge contract clauses from {@code source} into {@code target} when both
     * lift to the same JDK method. The target keeps its existing
     * preconditions and postconditions; any clauses on the source that aren't
     * already on the target are appended.
     */
    private static void mergeContractClauses(MethodDeclaration target,
                                             MethodDeclaration source,
                                             List<String> warnings) {
        var targetBody = target.getBody().orElseThrow();
        var sourceBody = source.getBody().orElseThrow();
        var targetClauses = new java.util.HashSet<String>();
        for (var stmt : targetBody.getStatements()) {
            if (isContractClause(stmt)) {
                targetClauses.add(stmt.toString());
            }
        }
        // Find the throw at the end of target so we insert clauses before it.
        int insertIndex = targetBody.getStatements().size();
        for (int i = targetBody.getStatements().size() - 1; i >= 0; i--) {
            if (targetBody.getStatement(i) instanceof ThrowStmt) {
                insertIndex = i;
                break;
            }
        }
        for (var stmt : sourceBody.getStatements()) {
            if (!isContractClause(stmt)) continue;
            if (targetClauses.contains(stmt.toString())) continue;
            targetBody.getStatements().add(insertIndex, stmt.clone());
            insertIndex++;
        }
        warnings.add("merged duplicate axiom for " + signatureKey(target)
            + " (multiple @AsProperty methods targeted the same JDK method)");
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

    /**
     * True when the {@code @AsProperty} method's parameter list does not
     * directly map onto the delegating JDK call's argument list. In the
     * common case the body is {@code return java.lang.X.method(p1, p2);}
     * where {@code p1, p2} are the same identifiers as the contract method's
     * parameters. If the body uses transformed inputs
     * (e.g. {@code Byte.parseByte(Byte.toString(b))} where {@code b} is a
     * {@code byte} parameter but the delegating call takes a {@code String}),
     * the contract is round-trip-style and doesn't lift to a single JDK
     * method's signature.
     */
    private static boolean delegatedArgsDontMatchParameters(MethodDeclaration original) {
        var bodyOpt = original.getBody();
        if (bodyOpt.isEmpty()) return false;
        com.github.javaparser.ast.expr.MethodCallExpr call = null;
        for (var stmt : bodyOpt.get().getStatements()) {
            if (stmt instanceof com.github.javaparser.ast.stmt.ReturnStmt ret
                    && ret.getExpression().isPresent()
                    && ret.getExpression().get() instanceof com.github.javaparser.ast.expr.MethodCallExpr c) {
                call = c;
                break;
            }
            if (stmt instanceof com.github.javaparser.ast.stmt.ExpressionStmt expr
                    && expr.getExpression() instanceof com.github.javaparser.ast.expr.MethodCallExpr c
                    && !c.getNameAsString().equals("precondition")
                    && !c.getNameAsString().equals("postcondition")) {
                call = c;
                break;
            }
        }
        if (call == null) return false;
        var args = call.getArguments();
        var params = original.getParameters();
        if (args.size() != params.size()) return true;
        for (int i = 0; i < args.size(); i++) {
            // Each argument must be a bare reference to the matching parameter.
            // Anything else (literal, transformation, expression) means the
            // contract method's parameters don't represent the JDK method's.
            if (!(args.get(i) instanceof com.github.javaparser.ast.expr.NameExpr nameExpr)
                    || !nameExpr.getNameAsString().equals(params.get(i).getNameAsString())) {
                return true;
            }
        }
        return false;
    }

    /** True for jqwik parameter annotations like {@code @ForAll}, {@code @IntRange}, etc. */
    private static boolean isJqwikAnnotation(AnnotationExpr ann) {
        String name = ann.getNameAsString();
        // Anything explicitly under net.jqwik.* — fully qualified.
        if (name.startsWith("net.jqwik.")) return true;
        // Common simple names from net.jqwik.api and net.jqwik.api.constraints.
        // We list these defensively; the broader filter below catches the rest
        // by their declaring import in stripJqwikImports.
        return name.equals("ForAll")
            || name.equals("Provide")
            || name.equals("From")
            || name.equals("IntRange")
            || name.equals("LongRange")
            || name.equals("ShortRange")
            || name.equals("ByteRange")
            || name.equals("CharRange")
            || name.equals("FloatRange")
            || name.equals("DoubleRange")
            || name.equals("BigRange")
            || name.equals("StringLength")
            || name.equals("Size")
            || name.equals("Positive")
            || name.equals("Negative")
            || name.equals("NotEmpty")
            || name.equals("NotBlank")
            || name.equals("WithNull")
            || name.equals("UniqueElements")
            || name.equals("AlphaChars")
            || name.equals("NumericChars")
            || name.equals("Whitespace")
            || name.equals("Chars")
            || name.equals("CharRangeFrom")
            || name.equals("Scale");
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

    /**
     * Compute the package declaration for a generated axiom file. The
     * verifier discovers contract classes by their {@code @Contract} annotation,
     * not their on-disk path, so all axiom files declare the same upstream
     * package {@code org.strata.jverify.builtin} regardless of which JDK
     * package they describe. The on-disk layout is determined separately by
     * the caller and may mirror the contracts/ tree for readability.
     */
    private static String axiomPackageFor(String jdkType) {
        return "org.strata.jverify.builtin";
    }
}

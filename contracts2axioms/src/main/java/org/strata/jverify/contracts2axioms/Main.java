package org.strata.jverify.contracts2axioms;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command-line entry point for {@code contracts2axioms}.
 *
 * <p>Usage:</p>
 * <pre>
 *     contracts2axioms &lt;input.java&gt; &lt;jdk-type&gt; [&lt;output.java&gt;]
 * </pre>
 *
 * <ul>
 *     <li>{@code input.java}: an {@code @AsProperty}-style contract file
 *         (the validation form that {@code contracts2jqwik} consumes).</li>
 *     <li>{@code jdk-type}: the fully qualified name of the JDK type the
 *         contract describes, e.g. {@code java.lang.Integer}. The output
 *         axiom file is annotated {@code @Contract(<jdk-type>.class)}.</li>
 *     <li>{@code output.java} (optional): if omitted, the generated source
 *         is printed to stdout. If a directory, the file
 *         {@code &lt;ClassName&gt;.java} (without the {@code Contract} suffix
 *         the validation form has) is written inside it.</li>
 * </ul>
 *
 * <p>Output convention: input
 * {@code contracts/jverify/specs/java/lang/IntegerContract.java}
 * (a class named {@code IntegerContract}) becomes an axiom file declared
 * in package {@code org.strata.jverify.builtin} and named
 * {@code IntegerContract.java} (same simple name, axiom package). The
 * {@code @Contract(java.lang.Integer.class)} annotation links it to the
 * JDK type for the verifier.</p>
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 2 || args.length > 3) {
            System.err.println("Usage: contracts2axioms <input.java> <jdk-type> [<output>]");
            System.exit(1);
        }

        Path inputPath = Path.of(args[0]);
        String jdkType = args[1];

        if (!Files.isRegularFile(inputPath)) {
            System.err.println("error: not a file: " + inputPath);
            System.exit(1);
        }

        String source = Files.readString(inputPath);
        Contracts2Axioms.Result result = Contracts2Axioms.translate(
            source, inputPath.getFileName().toString(), jdkType);

        for (String warning : result.warnings) {
            System.err.println("warning: " + warning);
        }

        if (result.output == null) {
            System.err.println("note: no @AsProperty methods found in " + inputPath);
            return;
        }

        if (args.length == 3) {
            Path outputPath = Path.of(args[2]);
            if (Files.isDirectory(outputPath)) {
                outputPath = outputPath.resolve(result.suggestedFileName);
            }
            Files.createDirectories(outputPath.toAbsolutePath().getParent());
            Files.writeString(outputPath, result.output);
            System.err.println("wrote " + outputPath);
        } else {
            System.out.print(result.output);
        }
    }
}

package io.ekbatan.core.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the processor through the real {@code javax.tools} compiler rather than a mock, because
 * the behaviour under test is precisely how a failure reaches javac: as a reported diagnostic or
 * as an escaping exception.
 */
class AutoBuilderProcessorTest {

    @TempDir
    Path outputDir;

    @Test
    void annotating_a_class_that_does_not_extend_model_reports_an_error_instead_of_crashing() throws IOException {
        // The processor reads ID and STATE off the superclass's type arguments. On a class with
        // none, that used to throw IndexOutOfBoundsException out of the processor, which javac
        // reports as "An annotation processor threw an uncaught exception" - a stack trace through
        // Ekbatan naming neither the annotated class nor the mistake.
        var diagnostics = compile("com.example.NotAModel", """
                package com.example;

                import io.ekbatan.core.processor.AutoBuilder;

                @AutoBuilder
                public class NotAModel {
                    String name;
                }
                """);

        var messages = diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> d.getMessage(null))
                .toList();

        assertThat(messages).isNotEmpty();
        assertThat(messages).anySatisfy(m -> assertThat(m).contains("@AutoBuilder requires"));
        assertThat(messages).anySatisfy(m -> assertThat(m).contains("com.example.NotAModel"));
        // The distinguishing assertion: javac's wording when a processor lets something escape.
        assertThat(messages).noneSatisfy(m -> assertThat(m).contains("uncaught exception"));
    }

    @Test
    void a_generic_domain_class_gets_its_type_variables_on_the_generated_builder() throws IOException {
        // The builder used to be declared bare while its fields and setters still referred to T,
        // so the generated file failed to compile on a symbol the user never wrote.
        var diagnostics = compile(
                new InMemorySource("io.ekbatan.core.domain.Model", """
                        package io.ekbatan.core.domain;

                        public abstract class Model<M, ID, STATE> {
                            public abstract static class Builder<ID, B, M, STATE> {
                                public abstract M build();
                            }
                        }
                        """), new InMemorySource("com.example.Box", """
                        package com.example;

                        import io.ekbatan.core.domain.Model;
                        import io.ekbatan.core.processor.AutoBuilder;

                        @AutoBuilder
                        public class Box<T> extends Model<Box<T>, String, String> {
                            T contents;
                        }
                        """));

        assertThat(errorsOf(diagnostics)).isEmpty();

        var generated = Files.readString(outputDir.resolve("classes/com/example/BoxBuilder.java"));
        assertThat(generated).contains("class BoxBuilder<T>");
        assertThat(generated).contains("T contents");
        // The static factory declares its own copy - a static method cannot see the class's.
        assertThat(generated).contains("static <T> BoxBuilder<T> box()");
    }

    @Test
    void a_non_generic_domain_class_is_generated_exactly_as_before() throws IOException {
        // The safety property behind the change: every existing @AutoBuilder class in the repo is
        // non-generic, and for those the type-variable list is empty and the output unchanged.
        var diagnostics = compile(
                new InMemorySource("io.ekbatan.core.domain.Model", """
                        package io.ekbatan.core.domain;

                        public abstract class Model<M, ID, STATE> {
                            public abstract static class Builder<ID, B, M, STATE> {
                                public abstract M build();
                            }
                        }
                        """), new InMemorySource("com.example.Wallet", """
                        package com.example;

                        import io.ekbatan.core.domain.Model;
                        import io.ekbatan.core.processor.AutoBuilder;

                        @AutoBuilder
                        public class Wallet extends Model<Wallet, String, String> {
                            String owner;
                        }
                        """));

        assertThat(errorsOf(diagnostics)).isEmpty();

        var generated = Files.readString(outputDir.resolve("classes/com/example/WalletBuilder.java"));
        assertThat(generated).contains("class WalletBuilder extends");
        assertThat(generated).doesNotContain("WalletBuilder<");
        assertThat(generated).contains("static WalletBuilder wallet()");
    }

    private static List<String> errorsOf(DiagnosticCollector<JavaFileObject> diagnostics) {
        return diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> d.getMessage(null))
                .toList();
    }

    private DiagnosticCollector<JavaFileObject> compile(InMemorySource... sources) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var classes = Files.createDirectories(outputDir.resolve("classes"));
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classes.toFile()));
            fileManager.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(classes.toFile()));

            var task = compiler.getTask(null, fileManager, diagnostics, List.of("-proc:only"), null, List.of(sources));
            task.setProcessors(List.of(new AutoBuilderProcessor()));
            task.call();
        }
        return diagnostics;
    }

    private DiagnosticCollector<JavaFileObject> compile(String qualifiedName, String source) throws IOException {
        return compile(new InMemorySource(qualifiedName, source));
    }

    private static final class InMemorySource extends SimpleJavaFileObject {

        private final String source;

        InMemorySource(String qualifiedName, String source) {
            super(URI.create("string:///" + qualifiedName.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }
}

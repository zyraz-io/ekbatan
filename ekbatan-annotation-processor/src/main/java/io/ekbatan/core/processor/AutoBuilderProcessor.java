package io.ekbatan.core.processor;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Generated;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;

@SupportedAnnotationTypes("io.ekbatan.core.processor.AutoBuilder")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public class AutoBuilderProcessor extends AbstractProcessor {

    private static final String MODEL_PACKAGE = "io.ekbatan.core.domain";
    private static final String MODEL_CLASS = "Model";
    private static final String ENTITY_CLASS = "Entity";
    private static final String BUILDER_CLASS = "Builder";

    private Elements elementUtils;
    private Filer filer;
    private Messager messager;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        elementUtils = processingEnv.getElementUtils();
        filer = processingEnv.getFiler();
        messager = processingEnv.getMessager();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(AutoBuilder.class)) {
            if (element.getKind() != ElementKind.CLASS) {
                error(element, "Only classes can be annotated with @AutoBuilder");
                return true;
            }

            TypeElement classElement = (TypeElement) element;
            try {
                generateBuilderClass(classElement);
            } catch (IOException e) {
                error(element, "Error generating builder for %s: %s", classElement.getQualifiedName(), e.getMessage());
            }
        }
        return true;
    }

    private void generateBuilderClass(TypeElement classElement) throws IOException {
        final var packageName =
                elementUtils.getPackageOf(classElement).getQualifiedName().toString();
        final var className = classElement.getSimpleName().toString();
        final var builderClassName = className + "Builder";

        // Get the type parameters from the superclass (Model or Entity)
        final var superclass = classElement.getSuperclass();
        final var typeArguments = ((DeclaredType) superclass).getTypeArguments();

        final var modelType = TypeName.get(classElement.asType());
        final var superClassName = ((TypeElement) ((DeclaredType) superclass).asElement())
                .getSimpleName()
                .toString();

        // Extract type parameters based on superclass type
        final var idType = TypeName.get(typeArguments.get(1)); // ID type is always second parameter
        final var stateType = TypeName.get(typeArguments.get(2)); // STATE type is always third parameter

        // Create the builder class with proper type parameters and @Generated annotation
        final var typeSpecBuilder = TypeSpec.classBuilder(builderClassName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addAnnotation(AnnotationSpec.builder(Generated.class)
                        .addMember("value", "$S", AutoBuilderProcessor.class.getName())
                        .build())
                .superclass(ParameterizedTypeName.get(
                        ClassName.get(MODEL_PACKAGE, superClassName, BUILDER_CLASS),
                        idType, // ID<M>
                        ClassName.get(packageName, builderClassName), // B (builder type)
                        modelType, // M (model type)
                        stateType // STATE (dynamic)
                        ));

        // Find all non-static fields in the target class
        List<VariableElement> fields = new ArrayList<>();
        for (Element enclosed : classElement.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.FIELD
                    && !enclosed.getModifiers().contains(Modifier.STATIC)) {
                fields.add((VariableElement) enclosed);
            }
        }

        // Add fields to the builder (without private modifier for package access)
        for (VariableElement field : fields) {
            String fieldName = field.getSimpleName().toString();
            TypeName fieldType = TypeName.get(field.asType());

            // Add the field (package-private)
            typeSpecBuilder.addField(fieldType, fieldName);

            MethodSpec setter = MethodSpec.methodBuilder(fieldName)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(ClassName.get(packageName, builderClassName))
                    .addParameter(fieldType, fieldName)
                    .addStatement("this.$N = $N", fieldName, fieldName)
                    .addStatement("return this")
                    .build();
            typeSpecBuilder.addMethod(setter);

            // Add the getter method
            final var getter = MethodSpec.methodBuilder(fieldName) // Same name as field
                    .addModifiers(Modifier.PUBLIC)
                    .returns(fieldType)
                    .addStatement("return this.$N", fieldName)
                    .build();
            typeSpecBuilder.addMethod(getter);
        }

        // Add private constructor
        final var constructor =
                MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build();
        typeSpecBuilder.addMethod(constructor);

        // Add static factory method (using classname in lowercase as method name)
        final var factoryMethodName =
                Character.toLowerCase(className.charAt(0)) + (className.length() > 1 ? className.substring(1) : "");
        final var factoryMethod = MethodSpec.methodBuilder(factoryMethodName)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(ClassName.get(packageName, builderClassName))
                .addStatement("return new $T()", ClassName.get(packageName, builderClassName))
                .build();
        typeSpecBuilder.addMethod(factoryMethod);

        // Add build method
        final var buildMethod = MethodSpec.methodBuilder("build")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(modelType)
                .addStatement("return new $T(this)", modelType)
                .build();
        typeSpecBuilder.addMethod(buildMethod);

        // Write the generated class to a file
        JavaFile.builder(packageName, typeSpecBuilder.build()).build().writeTo(filer);
    }

    private void error(Element e, String msg, Object... args) {
        messager.printMessage(Diagnostic.Kind.ERROR, String.format(msg, args), e);
    }
}

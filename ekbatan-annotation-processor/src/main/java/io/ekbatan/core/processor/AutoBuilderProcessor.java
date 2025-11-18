package io.ekbatan.core.processor;

import com.squareup.javapoet.*;
import java.io.IOException;
import java.util.*;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;

@SupportedAnnotationTypes("io.ekbatan.core.processor.AutoBuilder")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public class AutoBuilderProcessor extends AbstractProcessor {

    private static final String MODEL_PACKAGE = "io.ekbatan.core.domain";
    private static final String MODEL_CLASS = "Model";
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
        String packageName =
                elementUtils.getPackageOf(classElement).getQualifiedName().toString();
        String className = classElement.getSimpleName().toString();
        String builderClassName = className + "Builder";

        // Get the type parameters from the Model superclass
        TypeMirror superclass = classElement.getSuperclass();
        List<? extends TypeMirror> typeArguments = ((DeclaredType) superclass).getTypeArguments();

        // Extract the type parameters
        TypeName modelType = TypeName.get(classElement.asType());
        TypeName idType = TypeName.get(typeArguments.get(1)); // ID type
        TypeName stateType = TypeName.get(typeArguments.get(2)); // STATE type

        // Create the builder class with proper type parameters and @Generated annotation
        TypeSpec.Builder builder = TypeSpec.classBuilder(builderClassName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addAnnotation(AnnotationSpec.builder(Generated.class)
                        .addMember("value", "$S", AutoBuilderProcessor.class.getName())
                        .build())
                .superclass(ParameterizedTypeName.get(
                        ClassName.get(MODEL_PACKAGE, MODEL_CLASS, BUILDER_CLASS),
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
            builder.addField(fieldType, fieldName);

            MethodSpec setter = MethodSpec.methodBuilder(fieldName)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(ClassName.get(packageName, builderClassName))
                    .addParameter(fieldType, fieldName)
                    .addStatement("this.$N = $N", fieldName, fieldName)
                    .addStatement("return this")
                    .build();
            builder.addMethod(setter);

            // Add the getter method
            MethodSpec getter = MethodSpec.methodBuilder(fieldName) // Same name as field
                    .addModifiers(Modifier.PUBLIC)
                    .returns(fieldType)
                    .addStatement("return this.$N", fieldName)
                    .build();
            builder.addMethod(getter);
        }

        // Add private constructor
        MethodSpec constructor =
                MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build();
        builder.addMethod(constructor);

        // Add static factory method (using classname in lowercase as method name)
        String factoryMethodName =
                Character.toLowerCase(className.charAt(0)) + (className.length() > 1 ? className.substring(1) : "");
        MethodSpec factoryMethod = MethodSpec.methodBuilder(factoryMethodName)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(ClassName.get(packageName, builderClassName))
                .addStatement("return new $T()", ClassName.get(packageName, builderClassName))
                .build();
        builder.addMethod(factoryMethod);

        // Add build method
        MethodSpec buildMethod = MethodSpec.methodBuilder("build")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(modelType)
                .addStatement("return new $T(this)", modelType)
                .build();
        builder.addMethod(buildMethod);

        // Add copyBase method to support copying from existing model
        MethodSpec copyBaseMethod = MethodSpec.methodBuilder("copyBase")
                .addModifiers(Modifier.PUBLIC)
                .returns(ClassName.get(packageName, builderClassName))
                .addParameter(modelType, "source")
                .addStatement("super.copyBase(source)")
                .addStatement("return this")
                .build();
        builder.addMethod(copyBaseMethod);

        // Write the generated class to a file
        JavaFile.builder(packageName, builder.build()).build().writeTo(filer);
    }

    private void error(Element e, String msg, Object... args) {
        messager.printMessage(Diagnostic.Kind.ERROR, String.format(msg, args), e);
    }
}

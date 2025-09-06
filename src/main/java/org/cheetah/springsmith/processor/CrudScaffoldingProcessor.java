package org.cheetah.springsmith.processor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

import com.google.auto.service.AutoService;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

@AutoService(Processor.class)
@SupportedSourceVersion(SourceVersion.RELEASE_17)
@SupportedAnnotationTypes({
        "javax.persistence.Entity",
        "jakarta.persistence.Entity"
})
public class CrudScaffoldingProcessor extends AbstractProcessor {

    private Messager messager;
    private Elements elementUtils;
    private Types typeUtils;

    private static final String BASE_PACKAGE_SUFFIX_DTO = "dtos";
    private static final String BASE_PACKAGE_SUFFIX_MAPPER = "mappers";
    private static final String BASE_PACKAGE_SUFFIX_REPO = "repositories";
    private static final String BASE_PACKAGE_SUFFIX_SERVICE = "services";
    private static final String BASE_PACKAGE_SUFFIX_CONTROLLER = "controllers";

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.messager = processingEnv.getMessager();
        this.elementUtils = processingEnv.getElementUtils();
        this.typeUtils = processingEnv.getTypeUtils();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Set<Element> entities = new HashSet<>();
        TypeElement javaxEntity = elementUtils.getTypeElement("javax.persistence.Entity");
        TypeElement jakartaEntity = elementUtils.getTypeElement("jakarta.persistence.Entity");
        if (javaxEntity != null) {
            entities.addAll(roundEnv.getElementsAnnotatedWith(javaxEntity));
        }
        if (jakartaEntity != null) {
            entities.addAll(roundEnv.getElementsAnnotatedWith(jakartaEntity));
        }

        for (Element e : entities) {
            if (!(e instanceof TypeElement)) continue;
            TypeElement entity = (TypeElement) e;
            try {
                generateForEntity(entity);
            } catch (Exception ex) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "Errore generazione per " + entity.getSimpleName() + ": " + ex.getMessage());
            }
        }

        return true;
    }

    private void generateForEntity(TypeElement entity) throws IOException {
        String entitySimple = entity.getSimpleName().toString();
        String entityPackage = elementUtils.getPackageOf(entity).getQualifiedName().toString();

        // DTO
        TypeSpec dto = buildDto(entity, entitySimple);
        JavaFile.builder(replaceLastPackageSegment(entityPackage, BASE_PACKAGE_SUFFIX_DTO), dto)
                .build()
                .writeTo(processingEnv.getFiler());

        // Mapper
        TypeSpec mapper = buildMapper(entity, entitySimple);
        JavaFile.builder(replaceLastPackageSegment(entityPackage, BASE_PACKAGE_SUFFIX_MAPPER), mapper)
                .build()
                .writeTo(processingEnv.getFiler());

        // Repository
        TypeSpec repository = buildRepository(entity, entitySimple);
        JavaFile.builder(replaceLastPackageSegment(entityPackage, BASE_PACKAGE_SUFFIX_REPO), repository)
                .build()
                .writeTo(processingEnv.getFiler());

        // Service
        TypeSpec service = buildService(entity, entitySimple);
        JavaFile.builder(replaceLastPackageSegment(entityPackage, BASE_PACKAGE_SUFFIX_SERVICE), service)
                .build()
                .writeTo(processingEnv.getFiler());

        // Controller
        TypeSpec controller = buildController(entity, entitySimple);
        JavaFile.builder(replaceLastPackageSegment(entityPackage, BASE_PACKAGE_SUFFIX_CONTROLLER), controller)
                .build()
                .writeTo(processingEnv.getFiler());
    }

    private TypeSpec buildDto(TypeElement entity, String entitySimple) {
        String dtoName = entitySimple + "DTO";
        TypeSpec.Builder builder = TypeSpec.classBuilder(dtoName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(ClassName.get("com.fasterxml.jackson.annotation", "JsonInclude"))
                        .addMember("value", "$T.$L",
                                ClassName.get("com.fasterxml.jackson.annotation", "JsonInclude.Include"),
                                "NON_NULL")
                        .build());

        List<FieldSpec> fields = new ArrayList<>();

        for (VariableElement field : ElementFilter.fieldsIn(entity.getEnclosedElements())) {
            if (field.getModifiers().contains(Modifier.STATIC)) continue;

            // Skip OneToMany / ManyToMany collections
            if (isCollection(field) &&
                    (hasAnnotation(field, "javax.persistence.OneToMany") ||
                            hasAnnotation(field, "jakarta.persistence.OneToMany") ||
                            hasAnnotation(field, "javax.persistence.ManyToMany") ||
                            hasAnnotation(field, "jakarta.persistence.ManyToMany"))) {
                continue;
            }

            String fieldName;
            TypeName fieldType;

            if (hasAnnotation(field, "javax.persistence.ManyToOne") ||
                    hasAnnotation(field, "jakarta.persistence.ManyToOne") ||
                    hasAnnotation(field, "javax.persistence.OneToOne") ||
                    hasAnnotation(field, "jakarta.persistence.OneToOne")) {
                // only referenced ID, avoid double "Id"
                TypeMirror referenced = field.asType();
                Element referencedEl = typeUtils.asElement(referenced);
                if (referencedEl instanceof TypeElement) {
                    Optional<VariableElement> idField = findIdField((TypeElement) referencedEl);
                    if (idField.isPresent()) {
                        fieldType = TypeName.get(idField.get().asType());
                        String base = field.getSimpleName().toString();
                        fieldName = base.endsWith("Id") ? base : base + "Id";
                    } else {
                        continue;
                    }
                } else {
                    continue;
                }
            } else if (hasAnnotation(field, "javax.persistence.ManyToMany") ||
                    hasAnnotation(field, "jakarta.persistence.ManyToMany") ||
                    hasAnnotation(field, "javax.persistence.OneToMany") ||
                    hasAnnotation(field, "jakarta.persistence.OneToMany")) {
                continue; // skip
            } else {
                fieldType = TypeName.get(field.asType());
                fieldName = field.getSimpleName().toString();
            }

            FieldSpec fs = FieldSpec.builder(fieldType, fieldName, Modifier.PRIVATE).build();
            fields.add(fs);
            builder.addField(fs);

            // getter
            String getterName = (fieldType.equals(TypeName.BOOLEAN) || fieldType.equals(ClassName.get(Boolean.class)))
                    ? "is" + capitalize(fieldName)
                    : "get" + capitalize(fieldName);
            MethodSpec getter = MethodSpec.methodBuilder(getterName)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(fieldType)
                    .addStatement("return this.$N", fieldName)
                    .build();
            builder.addMethod(getter);

            // setter
            MethodSpec setter = MethodSpec.methodBuilder("set" + capitalize(fieldName))
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(fieldType, fieldName)
                    .addStatement("this.$N = $N", fieldName, fieldName)
                    .build();
            builder.addMethod(setter);
        }

        return builder.build();
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
private TypeSpec buildMapper(TypeElement entity, String entitySimple) {
    String dtoName = entitySimple + "DTO";
    String mapperName = entitySimple + "Mapper";
    String entityPkg = elementUtils.getPackageOf(entity).getQualifiedName().toString();

    ClassName entityClass = ClassName.get(entityPkg, entitySimple);
    ClassName dtoClass = ClassName.get(replaceLastPackageSegment(entityPkg, BASE_PACKAGE_SUFFIX_DTO), dtoName);

    AnnotationSpec mapperAnno = AnnotationSpec.builder(ClassName.get("org.mapstruct", "Mapper"))
            .addMember("componentModel", "$S", "spring")
            .addMember("unmappedTargetPolicy", "$T.IGNORE", ClassName.get("org.mapstruct", "ReportingPolicy"))
            .build();

    TypeSpec.Builder builder = TypeSpec.interfaceBuilder(mapperName)
            .addAnnotation(mapperAnno)
            .addModifiers(Modifier.PUBLIC);

    // ===== toDTO =====
    MethodSpec.Builder toDto = MethodSpec.methodBuilder("to" + entitySimple + "DTO")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .returns(dtoClass)
            .addParameter(entityClass, decap(entitySimple));

    for (VariableElement field : ElementFilter.fieldsIn(entity.getEnclosedElements())) {
        if (hasAnnotation(field, "javax.persistence.ManyToOne") ||
            hasAnnotation(field, "jakarta.persistence.ManyToOne") ||
            hasAnnotation(field, "javax.persistence.OneToOne") ||
            hasAnnotation(field, "jakarta.persistence.OneToOne")) {

            String fieldName = field.getSimpleName().toString();
            String target = fieldName.endsWith("Id") ? fieldName : fieldName + "Id";
            String source = fieldName + ".id";
            toDto.addAnnotation(AnnotationSpec.builder(ClassName.get("org.mapstruct", "Mapping"))
                    .addMember("source", "$S", source)
                    .addMember("target", "$S", target)
                    .build());
        }
    }
    builder.addMethod(toDto.build());

    // ===== toEntity =====
    MethodSpec.Builder toEntity = MethodSpec.methodBuilder("to" + entitySimple)
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .returns(entityClass)
            .addParameter(dtoClass, decap(dtoName));

    // Per evitare helper duplicati per lo stesso tipo relazione
    Set<String> helperGeneratedFor = new HashSet<>();

    for (VariableElement field : ElementFilter.fieldsIn(entity.getEnclosedElements())) {
        boolean isRelation =
                hasAnnotation(field, "javax.persistence.ManyToOne") ||
                hasAnnotation(field, "jakarta.persistence.ManyToOne") ||
                hasAnnotation(field, "javax.persistence.OneToOne") ||
                hasAnnotation(field, "jakarta.persistence.OneToOne");

        if (!isRelation) continue;

        String fieldName = field.getSimpleName().toString();
        String sourceIdName = fieldName.endsWith("Id") ? fieldName : fieldName + "Id";

        // Mappiamo <campo>Id (DTO) -> <campo> (Entity)
        toEntity.addAnnotation(AnnotationSpec.builder(ClassName.get("org.mapstruct", "Mapping"))
                .addMember("target", "$S", fieldName)
                .addMember("source", "$S", sourceIdName)
                .build());

        // Genera helper: IdType -> RelatedEntity (una sola volta per tipo)
        TypeElement relatedEl = (TypeElement) typeUtils.asElement(field.asType());
        if (relatedEl != null) {
            Optional<VariableElement> relatedIdField = findIdField(relatedEl);
            if (relatedIdField.isPresent()) {
                String relatedFqn = relatedEl.getQualifiedName().toString();
                if (helperGeneratedFor.add(relatedFqn)) {
                    String relatedSimple = relatedEl.getSimpleName().toString();
                    String relatedPkg = elementUtils.getPackageOf(relatedEl).getQualifiedName().toString();
                    ClassName relatedClass = ClassName.get(relatedPkg, relatedSimple);

                    String idFieldName = relatedIdField.get().getSimpleName().toString();
                    TypeName idType = TypeName.get(relatedIdField.get().asType());

                    MethodSpec helper = MethodSpec.methodBuilder("map" + relatedSimple + "FromId")
                            .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
                            .returns(relatedClass)
                            .addParameter(idType, "id")
                            .beginControlFlow("if (id == null)")
                            .addStatement("return null")
                            .endControlFlow()
                            .addStatement("$T e = new $T()", relatedClass, relatedClass)
                            .addStatement("e.set$L(id)", capitalize(idFieldName))
                            .addStatement("return e")
                            .build();

                    builder.addMethod(helper);
                }
            } else {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "Relazione '" + fieldName + "' -> " + relatedEl.getSimpleName() + " senza @Id; non genero helper.");
            }
        }
    }

    builder.addMethod(toEntity.build());

    return builder.build();
}

private TypeSpec buildRepository(TypeElement entity, String entitySimple) {
        String repoName = entitySimple + "Repository";
        String entityPkg = elementUtils.getPackageOf(entity).getQualifiedName().toString();

        Optional<VariableElement> idFieldOpt = findIdField(entity);
        if (idFieldOpt.isEmpty()) {
            messager.printMessage(Diagnostic.Kind.WARNING, "Entity " + entitySimple + " has no @Id; skipping repository.");
            return TypeSpec.interfaceBuilder(repoName).addModifiers(Modifier.PUBLIC).build();
        }
        TypeName idType = TypeName.get(idFieldOpt.get().asType());
        ClassName entityClass = ClassName.get(entityPkg, entitySimple);
        ParameterizedTypeName superInterface = ParameterizedTypeName.get(
                ClassName.get("org.springframework.data.jpa.repository", "JpaRepository"),
                entityClass,
                idType
        );

        return TypeSpec.interfaceBuilder(repoName)
                .addModifiers(Modifier.PUBLIC)
                .addSuperinterface(superInterface)
                .addAnnotation(ClassName.get("org.springframework.stereotype", "Repository"))
                .build();
    }

    private TypeSpec buildService(TypeElement entity, String entitySimple) {
        String serviceName = entitySimple + "Service";
        String dtoName = entitySimple + "DTO";
        String repoName = entitySimple + "Repository";
        String mapperName = entitySimple + "Mapper";

        String entityPkg = elementUtils.getPackageOf(entity).getQualifiedName().toString();

        ClassName entityClass = ClassName.get(entityPkg, entitySimple);
        ClassName dtoClass = ClassName.get(replaceLastPackageSegment(entityPkg, BASE_PACKAGE_SUFFIX_DTO), dtoName);
        ClassName repoClass = ClassName.get(replaceLastPackageSegment(entityPkg, BASE_PACKAGE_SUFFIX_REPO), repoName);
        ClassName mapperClass = ClassName.get(replaceLastPackageSegment(entityPkg, BASE_PACKAGE_SUFFIX_MAPPER), mapperName);

        Optional<VariableElement> idFieldOpt = findIdField(entity);
        String idFieldName = idFieldOpt.map(VariableElement::getSimpleName).map(Object::toString).orElse("id");
        TypeName idType = idFieldOpt.map(f -> TypeName.get(f.asType())).orElse(ClassName.get(Long.class));

        TypeSpec.Builder builder = TypeSpec.classBuilder(serviceName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(ClassName.get("org.springframework.stereotype", "Service"));

        builder.addField(FieldSpec.builder(repoClass, decap(repoName), Modifier.PRIVATE, Modifier.FINAL).build());
        builder.addField(FieldSpec.builder(mapperClass, decap(mapperName), Modifier.PRIVATE, Modifier.FINAL).build());

        MethodSpec constructor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(repoClass, decap(repoName))
                .addParameter(mapperClass, decap(mapperName))
                .addStatement("this.$N = $N", decap(repoName), decap(repoName))
                .addStatement("this.$N = $N", decap(mapperName), decap(mapperName))
                .build();
        builder.addMethod(constructor);

        MethodSpec findAll = MethodSpec.methodBuilder("findAll")
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(ClassName.get(List.class), dtoClass))
                .addStatement("$T entities = $N.findAll()", ParameterizedTypeName.get(ClassName.get(List.class), entityClass), decap(repoName))
                .addStatement("return entities.stream().map($N::to$LDTO).collect($T.toList())",
                        decap(mapperName), entitySimple, ClassName.get("java.util.stream", "Collectors"))
                .build();
        builder.addMethod(findAll);

        MethodSpec findById = MethodSpec.methodBuilder("findById")
                .addModifiers(Modifier.PUBLIC)
                .returns(dtoClass)
                .addParameter(idType, idFieldName)
                .addStatement("$T entity = $N.findById($N).orElseThrow(() -> new $T($S))",
                        entityClass, decap(repoName), idFieldName,
                        ClassName.get("jakarta.persistence", "EntityNotFoundException"),
                        entitySimple + " not found with id " + "\" + " + idFieldName + " + \"")
                .addStatement("return $N.to$LDTO(entity)", decap(mapperName), entitySimple)
                .build();
        builder.addMethod(findById);

        MethodSpec save = MethodSpec.methodBuilder("save")
                .addModifiers(Modifier.PUBLIC)
                .returns(dtoClass)
                .addParameter(dtoClass, "dto")
                .addStatement("$T entity = $N.to$L(dto)", entityClass, decap(mapperName), entitySimple)
                .addStatement("$T saved = $N.save(entity)", entityClass, decap(repoName))
                .addStatement("return $N.to$LDTO(saved)", decap(mapperName), entitySimple)
                .build();
        builder.addMethod(save);

        MethodSpec update = MethodSpec.methodBuilder("update")
                .addModifiers(Modifier.PUBLIC)
                .returns(dtoClass)
                .addParameter(idType, idFieldName)
                .addParameter(dtoClass, "dto")
                .addStatement("$T existing = $N.findById($N).orElseThrow(() -> new $T($S))",
                        entityClass, decap(repoName), idFieldName,
                        ClassName.get("jakarta.persistence", "EntityNotFoundException"),
                        entitySimple + " not found with id " + "\" + " + idFieldName + " + \"")
                .addStatement("existing = $N.to$L(dto)", decap(mapperName), entitySimple)
                .addStatement("$T updated = $N.save(existing)", entityClass, decap(repoName))
                .addStatement("return $N.to$LDTO(updated)", decap(mapperName), entitySimple)
                .build();
        builder.addMethod(update);

        MethodSpec delete = MethodSpec.methodBuilder("delete")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(idType, idFieldName)
                .addStatement("$N.deleteById($N)", decap(repoName), idFieldName)
                .build();
        builder.addMethod(delete);

        return builder.build();
    }

    private TypeSpec buildController(TypeElement entity, String entitySimple) {
        String controllerName = entitySimple + "Controller";
        String serviceName = entitySimple + "Service";
        String dtoName = entitySimple + "DTO";

        String entityPkg = elementUtils.getPackageOf(entity).getQualifiedName().toString();
        String path = "/" + pluralize(lowerFirst(entitySimple));

        ClassName serviceClass = ClassName.get(replaceLastPackageSegment(entityPkg, BASE_PACKAGE_SUFFIX_SERVICE), serviceName);
        ClassName dtoClass = ClassName.get(replaceLastPackageSegment(entityPkg, BASE_PACKAGE_SUFFIX_DTO), dtoName);

        Optional<VariableElement> idFieldOpt = findIdField(entity);
        TypeName idType = idFieldOpt.map(f -> TypeName.get(f.asType())).orElse(ClassName.get(Long.class));
        String idName = idFieldOpt.map(VariableElement::getSimpleName).map(Object::toString).orElse("id");

        TypeSpec.Builder builder = TypeSpec.classBuilder(controllerName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(ClassName.get("org.springframework.web.bind.annotation", "RestController")).build())
                .addAnnotation(AnnotationSpec.builder(ClassName.get("org.springframework.web.bind.annotation", "RequestMapping"))
                        .addMember("value", "$S", "/api" + path)
                        .build());

        builder.addField(FieldSpec.builder(serviceClass, decap(serviceName), Modifier.PRIVATE, Modifier.FINAL).build());
        MethodSpec constructor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(serviceClass, decap(serviceName))
                .addStatement("this.$N = $N", decap(serviceName), decap(serviceName))
                .build();
        builder.addMethod(constructor);

        MethodSpec getAll = MethodSpec.methodBuilder("getAll" + pluralize(entitySimple))
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(ClassName.get("org.springframework.web.bind.annotation", "GetMapping")).build())
                .returns(ParameterizedTypeName.get(ClassName.get(List.class), dtoClass))
                .addStatement("return $N.findAll()", decap(serviceName))
                .build();
        builder.addMethod(getAll);

        MethodSpec getById = MethodSpec.methodBuilder("get" + entitySimple + "ById")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(ClassName.get("org.springframework.web.bind.annotation", "GetMapping"))
                        .addMember("value", "$S", "/{" + idName + "}")
                        .build())
                .addParameter(ParameterSpec.builder(idType, idName)
                        .addAnnotation(AnnotationSpec.builder(ClassName.get("org.springframework.web.bind.annotation", "PathVariable")).build())
                        .build())
                .returns(ParameterizedTypeName.get(ClassName.get("org.springframework.http", "ResponseEntity"), dtoClass))
                .addStatement("$T dto = $N.findById($N)", dtoClass, decap(serviceName), idName)
                .addStatement("return $T.ok(dto)", ClassName.get("org.springframework.http", "ResponseEntity"))
                .build();
        builder.addMethod(getById);

        MethodSpec create = MethodSpec.methodBuilder("create" + entitySimple)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(ClassName.get("org.springframework.web.bind.annotation", "PostMapping")).build())
                .addParameter(ParameterSpec.builder(dtoClass, "dto")
                        .addAnnotation(AnnotationSpec.builder(ClassName.get("org.springframework.web.bind.annotation", "RequestBody")).build())
                        .build())
                .returns(ParameterizedTypeName.get(ClassName.get("org.springframework.http", "ResponseEntity"), dtoClass))
                .addStatement("$T created = $N.save(dto)", dtoClass, decap(serviceName))
                .addStatement("return $T.status($T.CREATED).body(created)",
                        ClassName.get("org.springframework.http", "ResponseEntity"),
                        ClassName.get("org.springframework.http", "HttpStatus"))
                .build();
        builder.addMethod(create);

        MethodSpec update = MethodSpec.methodBuilder("update" + entitySimple)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(ClassName.get("org.springframework.web.bind.annotation", "PutMapping"))
                        .addMember("value", "$S", "/{" + idName + "}")
                        .build())
                .addParameter(ParameterSpec.builder(idType, idName)
                        .addAnnotation(AnnotationSpec.builder(ClassName.get("org.springframework.web.bind.annotation", "PathVariable")).build())
                        .build())
                .addParameter(ParameterSpec.builder(dtoClass, "dto")
                        .addAnnotation(AnnotationSpec.builder(ClassName.get("org.springframework.web.bind.annotation", "RequestBody")).build())
                        .build())
                .returns(ParameterizedTypeName.get(ClassName.get("org.springframework.http", "ResponseEntity"), dtoClass))
                .addStatement("$T updated = $N.update($N, dto)", dtoClass, decap(serviceName), idName)
                .addStatement("return $T.ok(updated)", ClassName.get("org.springframework.http", "ResponseEntity"))
                .build();
        builder.addMethod(update);

        MethodSpec delete = MethodSpec.methodBuilder("delete" + entitySimple)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(ClassName.get("org.springframework.web.bind.annotation", "DeleteMapping"))
                        .addMember("value", "$S", "/{" + idName + "}")
                        .build())
                .addParameter(ParameterSpec.builder(idType, idName)
                        .addAnnotation(AnnotationSpec.builder(ClassName.get("org.springframework.web.bind.annotation", "PathVariable")).build())
                        .build())
                .returns(ParameterizedTypeName.get(
                        ClassName.get("org.springframework.http", "ResponseEntity"),
                        ClassName.get(Void.class)))
                .addStatement("$N.delete($N)", decap(serviceName), idName)
                .addStatement("return $T.noContent().build()", ClassName.get("org.springframework.http", "ResponseEntity"))
                .build();
        builder.addMethod(delete);

        return builder.build();
    }

    private Optional<VariableElement> findIdField(TypeElement entity) {
        for (VariableElement field : ElementFilter.fieldsIn(entity.getEnclosedElements())) {
            if (hasAnnotation(field, "javax.persistence.Id") ||
                    hasAnnotation(field, "jakarta.persistence.Id")) {
                return Optional.of(field);
            }
        }
        return Optional.empty();
    }

    private boolean hasAnnotation(Element e, String annotationFqn) {
        for (AnnotationMirror am : e.getAnnotationMirrors()) {
            if (am.getAnnotationType().toString().equals(annotationFqn)) return true;
        }
        return false;
    }

    private boolean isCollection(VariableElement field) {
        TypeMirror t = field.asType();
        return typeUtils.isAssignable(t, elementUtils.getTypeElement("java.util.Collection").asType())
                || typeUtils.isAssignable(t, elementUtils.getTypeElement("java.util.List").asType())
                || typeUtils.isAssignable(t, elementUtils.getTypeElement("java.util.Set").asType());
    }

    private String replaceLastPackageSegment(String original, String newLastSegment) {
        int lastDot = original.lastIndexOf('.');
        if (lastDot == -1) {
            return newLastSegment;
        }
        return original.substring(0, lastDot + 1) + newLastSegment;
    }

    private String decap(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private String lowerFirst(String s) {
        return decap(s);
    }

    private String pluralize(String s) {
        if (s.endsWith("y") && s.length() > 1) {
            return s.substring(0, s.length() - 1) + "ies";
        }
        if (s.endsWith("s")) {
            return s + "es";
        }
        return s + "s";
    }
}
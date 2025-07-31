package a;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

import org.springframework.javapoet.AnnotationSpec;
import org.springframework.javapoet.ClassName;
import org.springframework.javapoet.FieldSpec;
import org.springframework.javapoet.JavaFile;
import org.springframework.javapoet.MethodSpec;
import org.springframework.javapoet.ParameterSpec;
import org.springframework.javapoet.ParameterizedTypeName;
import org.springframework.javapoet.TypeName;
import org.springframework.javapoet.TypeSpec;

import com.google.auto.service.AutoService;

@AutoService(Processor.class)
@SupportedOptions({})
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
                messager.printMessage(Diagnostic.Kind.ERROR, "Errore generazione per " + entity.getSimpleName() + ": " + ex.getMessage());
            }
        }
        return true;
    }

    private void generateForEntity(TypeElement entity) throws IOException {
        String entitySimple = entity.getSimpleName().toString();
        PackageElement pkg = elementUtils.getPackageOf(entity);
        String entityPackage = pkg.getQualifiedName().toString();
        String basePackage = deriveBasePackage(entityPackage);

        // DTO
        TypeSpec dto = buildDto(entity, entitySimple, basePackage);
        JavaFile.builder(replaceLastPackageSegment(entityPackage, BASE_PACKAGE_SUFFIX_DTO), dto)
                .build()
                .writeTo(processingEnv.getFiler());

        // Mapper
        TypeSpec mapper = buildMapper(entity, entitySimple, basePackage);
        JavaFile.builder(replaceLastPackageSegment(entityPackage, BASE_PACKAGE_SUFFIX_MAPPER), mapper)
                .build()
                .writeTo(processingEnv.getFiler());

        // Repository
        TypeSpec repository = buildRepository(entity, entitySimple, basePackage);
        JavaFile.builder(replaceLastPackageSegment(entityPackage, BASE_PACKAGE_SUFFIX_REPO), repository)
                .build()
                .writeTo(processingEnv.getFiler());

        // Service
        TypeSpec service = buildService(entity, entitySimple, basePackage);
        JavaFile.builder(replaceLastPackageSegment(entityPackage, BASE_PACKAGE_SUFFIX_SERVICE), service)
                .build()
                .writeTo(processingEnv.getFiler());

        // Controller
        TypeSpec controller = buildController(entity, entitySimple, basePackage);
        JavaFile.builder(replaceLastPackageSegment(entityPackage, BASE_PACKAGE_SUFFIX_CONTROLLER), controller)
                .build()
                .writeTo(processingEnv.getFiler());
    }

    private String deriveBasePackage(String entityPackage) {
        int lastDot = entityPackage.lastIndexOf('.');
        if (lastDot == -1) return entityPackage;
        return entityPackage.substring(0, lastDot + 1);
    }

    private String replaceLastPackageSegment(String original, String newLastSegment) {
        int lastDot = original.lastIndexOf('.');
        if (lastDot == -1) {
            return newLastSegment;
        }
        return original.substring(0, lastDot + 1) + newLastSegment;
    }

    private TypeSpec buildDto(TypeElement entity, String entitySimple, String basePackage) {
        String dtoName = entitySimple + "DTO";
        TypeSpec.Builder builder = TypeSpec.classBuilder(dtoName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(ClassName.get("lombok", "Data"))
                .addAnnotation(ClassName.get("lombok", "NoArgsConstructor"))
                .addAnnotation(ClassName.get("lombok", "AllArgsConstructor"))
                .addAnnotation(AnnotationSpec.builder(ClassName.get("com.fasterxml.jackson.annotation", "JsonInclude"))
                        .addMember("value", "$T.$L", ClassName.get("com.fasterxml.jackson.annotation", "JsonInclude.Include"), "NON_NULL")
                        .build());

        // fields
        for (VariableElement field : ElementFilter.fieldsIn(entity.getEnclosedElements())) {
            if (field.getModifiers().contains(Modifier.STATIC)) continue;
            if (isCollection(field)) {
                // skip OneToMany / ManyToMany
                if (hasAnnotation(field, "javax.persistence.OneToMany") ||
                        hasAnnotation(field, "jakarta.persistence.OneToMany") ||
                        hasAnnotation(field, "javax.persistence.ManyToMany") ||
                        hasAnnotation(field, "jakarta.persistence.ManyToMany")) {
                    continue;
                }
            }
            if (hasAnnotation(field, "javax.persistence.ManyToOne") ||
                    hasAnnotation(field, "jakarta.persistence.ManyToOne") ||
                    hasAnnotation(field, "javax.persistence.OneToOne") ||
                    hasAnnotation(field, "jakarta.persistence.OneToOne")) {
                // include only reference id
                TypeMirror referenced = field.asType();
                TypeElement refElement = (TypeElement) typeUtils.asElement(referenced);
                Optional<VariableElement> idField = findIdField(refElement);
                if (idField.isPresent()) {
                    TypeName idType = TypeName.get(idField.get().asType());
                    String name = field.getSimpleName().toString() + "Id";
                    builder.addField(FieldSpec.builder(idType, name, Modifier.PRIVATE).build());
                }
                continue;
            }
            if (hasAnnotation(field, "javax.persistence.ManyToMany") ||
                    hasAnnotation(field, "jakarta.persistence.ManyToMany") ||
                    hasAnnotation(field, "javax.persistence.OneToMany") ||
                    hasAnnotation(field, "jakarta.persistence.OneToMany")) {
                continue; // skip
            }
            // normal field
            TypeName typeName = TypeName.get(field.asType());
            String name = field.getSimpleName().toString();
            builder.addField(FieldSpec.builder(typeName, name, Modifier.PRIVATE).build());
        }
        return builder.build();
    }

    private MethodSpec generateGetter(String name, TypeName type) {
        String methodName = "get" + capitalize(name);
        return MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PUBLIC)
                .returns(type)
                .addStatement("return this.$N", name)
                .build();
    }

    private MethodSpec generateSetter(String name, TypeName type) {
        String methodName = "set" + capitalize(name);
        return MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(type, name)
                .addStatement("this.$N = $N", name, name)
                .build();
    }

    private TypeSpec buildMapper(TypeElement entity, String entitySimple, String basePackage) {
        String dtoName = entitySimple + "DTO";
        String mapperName = entitySimple + "Mapper";
        ClassName entityClass = ClassName.get(elementUtils.getPackageOf(entity).getQualifiedName().toString(), entitySimple);
        ClassName dtoClass = ClassName.get(replaceLastPackageSegment(elementUtils.getPackageOf(entity).getQualifiedName().toString(), BASE_PACKAGE_SUFFIX_DTO), dtoName);

        AnnotationSpec mapperAnno = AnnotationSpec.builder(ClassName.get("org.mapstruct", "Mapper"))
                .addMember("componentModel", "$S", "spring")
                .addMember("unmappedTargetPolicy", "$T.IGNORE", ClassName.get("org.mapstruct", "ReportingPolicy"))
                .build();

        TypeSpec.Builder builder = TypeSpec.interfaceBuilder(mapperName)
                .addAnnotation(mapperAnno)
                .addModifiers(Modifier.PUBLIC);

        // toDTO with @Mapping for ManyToOne -> id
        MethodSpec.Builder toDto = MethodSpec.methodBuilder("to" + entitySimple + "DTO")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(dtoClass)
                .addParameter(entityClass, decap(entitySimple));
        // add mappings for ManyToOne
        for (VariableElement field : ElementFilter.fieldsIn(entity.getEnclosedElements())) {
            if (hasAnnotation(field, "javax.persistence.ManyToOne") ||
                    hasAnnotation(field, "jakarta.persistence.ManyToOne") ||
                    hasAnnotation(field, "javax.persistence.OneToOne") ||
                    hasAnnotation(field, "jakarta.persistence.OneToOne")) {
                String fieldName = field.getSimpleName().toString();
                String target = fieldName + "Id";
                String source = fieldName + ".id";
                toDto.addAnnotation(AnnotationSpec.builder(ClassName.get("org.mapstruct", "Mapping"))
                        .addMember("source", "$S", source)
                        .addMember("target", "$S", target)
                        .build());
            }
        }
        toDto.addParameter(entityClass, decap(entitySimple));
        builder.addMethod(toDto.build());

        // toEntity: ignore complex relation, let service gestire
        MethodSpec.Builder toEntity = MethodSpec.methodBuilder("to" + entitySimple)
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(entityClass)
                .addParameter(dtoClass, decap(dtoName));

        // ignore mappings for related objects (ManyToOne)
        for (VariableElement field : ElementFilter.fieldsIn(entity.getEnclosedElements())) {
            if (hasAnnotation(field, "javax.persistence.ManyToOne") ||
                    hasAnnotation(field, "jakarta.persistence.ManyToOne") ||
                    hasAnnotation(field, "javax.persistence.OneToOne") ||
                    hasAnnotation(field, "jakarta.persistence.OneToOne")) {
                String fieldName = field.getSimpleName().toString();
                toEntity.addAnnotation(AnnotationSpec.builder(ClassName.get("org.mapstruct", "Mapping"))
                        .addMember("target", "$S", fieldName)
                        .addMember("ignore", "true")
                        .build());
            }
        }
        builder.addMethod(toEntity.build());

        return builder.build();
    }

    private TypeSpec buildRepository(TypeElement entity, String entitySimple, String basePackage) {
        String repoName = entitySimple + "Repository";
        TypeElement jpaRepoElement = elementUtils.getTypeElement("org.springframework.data.jpa.repository.JpaRepository");
        Optional<VariableElement> idFieldOpt = findIdField(entity);
        if (idFieldOpt.isEmpty()) {
            messager.printMessage(Diagnostic.Kind.WARNING, "Entità " + entitySimple + " senza @Id: salta repository");
            return TypeSpec.interfaceBuilder("Empty").build();
        }
        TypeName idType = TypeName.get(idFieldOpt.get().asType());
        ClassName entityClass = ClassName.get(elementUtils.getPackageOf(entity).getQualifiedName().toString(), entitySimple);
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

    private TypeSpec buildService(TypeElement entity, String entitySimple, String basePackage) {
        String serviceName = entitySimple + "Service";
        String dtoName = entitySimple + "DTO";
        String repoName = entitySimple + "Repository";
        String mapperName = entitySimple + "Mapper";

        ClassName entityClass = ClassName.get(elementUtils.getPackageOf(entity).getQualifiedName().toString(), entitySimple);
        ClassName dtoClass = ClassName.get(replaceLastPackageSegment(elementUtils.getPackageOf(entity).getQualifiedName().toString(), BASE_PACKAGE_SUFFIX_DTO), dtoName);
        ClassName repoClass = ClassName.get(replaceLastPackageSegment(elementUtils.getPackageOf(entity).getQualifiedName().toString(), BASE_PACKAGE_SUFFIX_REPO), repoName);
        ClassName mapperClass = ClassName.get(replaceLastPackageSegment(elementUtils.getPackageOf(entity).getQualifiedName().toString(), BASE_PACKAGE_SUFFIX_MAPPER), mapperName);

        String idFieldName = findIdField(entity).map(VariableElement::getSimpleName).map(Object::toString).orElse("id");
        TypeName idType = findIdField(entity).map(f -> TypeName.get(f.asType())).orElse(TypeName.OBJECT);

        TypeSpec.Builder builder = TypeSpec.classBuilder(serviceName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(ClassName.get("org.springframework.stereotype", "Service"));

        // fields
        FieldSpec repoField = FieldSpec.builder(repoClass, decap(repoName), Modifier.PRIVATE, Modifier.FINAL).build();
        FieldSpec mapperField = FieldSpec.builder(mapperClass, decap(mapperName), Modifier.PRIVATE, Modifier.FINAL).build();
        builder.addField(repoField);
        builder.addField(mapperField);

        // constructor
        MethodSpec constructor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(repoClass, decap(repoName))
                .addParameter(mapperClass, decap(mapperName))
                .addStatement("this.$N = $N", decap(repoName), decap(repoName))
                .addStatement("this.$N = $N", decap(mapperName), decap(mapperName))
                .build();
        builder.addMethod(constructor);

        // findAll
        MethodSpec findAll = MethodSpec.methodBuilder("findAll")
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(ClassName.get(List.class), dtoClass))
                .addStatement("java.util.List<$T> entities = $N.findAll()", entityClass, decap(repoName))
                .addStatement("return entities.stream().map($N::to$LDTO).collect($T.toList())",
                        decap(mapperName), entitySimple, Collectors.class)
                .build();
        builder.addMethod(findAll);

        // save
        MethodSpec save = MethodSpec.methodBuilder("save")
                .addModifiers(Modifier.PUBLIC)
                .returns(dtoClass)
                .addParameter(dtoClass, "dto")
                .addStatement("$T entity = $N.to$L(dto)", entityClass, decap(mapperName), entitySimple)
                .addStatement("$T saved = $N.save(entity)", entityClass, decap(repoName))
                .addStatement("return $N.to$LDTO(saved)", decap(mapperName), entitySimple)
                .build();
        builder.addMethod(save);

        // update
        MethodSpec update = MethodSpec.methodBuilder("update")
                .addModifiers(Modifier.PUBLIC)
                .returns(dtoClass)
                .addParameter(idType, idFieldName)
                .addParameter(dtoClass, "dto")
                .addStatement("$T entity = $N.findById($N).orElseThrow(() -> new $T($S))",
                        entityClass, decap(repoName), idFieldName,
                        ClassName.get("jakarta.persistence", "EntityNotFoundException"),
                        entitySimple + " non trovato con id " + "\" + " + idFieldName + " + \"")
                // semplice aggiornamento: rigenera da dto salvo
                .addStatement("entity = $N.to$L(dto)", decap(mapperName), entitySimple)
                .addStatement("$T updated = $N.save(entity)", entityClass, decap(repoName))
                .addStatement("return $N.to$LDTO(updated)", decap(mapperName), entitySimple)
                .build();
        builder.addMethod(update);

        // delete
        MethodSpec delete = MethodSpec.methodBuilder("delete")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(idType, idFieldName)
                .addStatement("$N.deleteById($N)", decap(repoName), idFieldName)
                .build();
        builder.addMethod(delete);

        return builder.build();
    }

    private TypeSpec buildController(TypeElement entity, String entitySimple, String basePackage) {
        String controllerName = entitySimple + "Controller";
        String serviceName = entitySimple + "Service";
        String dtoName = entitySimple + "DTO";

        String path = "/" + pluralize(lowerFirst(entitySimple));
        ClassName serviceClass = ClassName.get(replaceLastPackageSegment(elementUtils.getPackageOf(entity).getQualifiedName().toString(), BASE_PACKAGE_SUFFIX_SERVICE), serviceName);
        ClassName dtoClass = ClassName.get(replaceLastPackageSegment(elementUtils.getPackageOf(entity).getQualifiedName().toString(), BASE_PACKAGE_SUFFIX_DTO), dtoName);
        Optional<VariableElement> idFieldOpt = findIdField(entity);
        TypeName idType = idFieldOpt.map(f -> TypeName.get(f.asType())).orElse(ClassName.get(Long.class));
        String idName = idFieldOpt.map(VariableElement::getSimpleName).map(Object::toString).orElse("id");

        TypeSpec.Builder builder = TypeSpec.classBuilder(controllerName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(ClassName.get("org.springframework.web.bind.annotation", "RestController")).build())
                .addAnnotation(AnnotationSpec.builder(ClassName.get("org.springframework.web.bind.annotation", "RequestMapping"))
                        .addMember("value", "$S", "/api" + path)
                        .build());

        // service field + constructor
        FieldSpec serviceField = FieldSpec.builder(serviceClass, decap(serviceName), Modifier.PRIVATE, Modifier.FINAL).build();
        builder.addField(serviceField);
        MethodSpec constructor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(serviceClass, decap(serviceName))
                .addStatement("this.$N = $N", decap(serviceName), decap(serviceName))
                .build();
        builder.addMethod(constructor);

        // GET all
        MethodSpec getAll = MethodSpec.methodBuilder("getAll" + pluralize(entitySimple))
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(ClassName.get("org.springframework.web.bind.annotation", "GetMapping")).build())
                .returns(ParameterizedTypeName.get(ClassName.get(List.class), dtoClass))
                .addStatement("return $N.findAll()", decap(serviceName))
                .build();
        builder.addMethod(getAll);

        // GET by id
        MethodSpec getById = MethodSpec.methodBuilder("get" + entitySimple + "ById")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(ClassName.get("org.springframework.web.bind.annotation", "GetMapping"))
                        .addMember("value", "$S", "/{" + idName + "}")
                        .build())
                .addParameter(ParameterSpec.builder(idType, idName)
                        .addAnnotation(AnnotationSpec.builder(ClassName.get("org.springframework.web.bind.annotation", "PathVariable")).build())
                        .build())
                .returns(ParameterizedTypeName.get(ClassName.get("org.springframework.http", "ResponseEntity"), dtoClass))
                .addStatement("$T dto = $N.update($N, null)", dtoClass, decap(serviceName), idName) // placeholder unless you implement findById separately
                .addStatement("return $T.ok(dto)", ClassName.get("org.springframework.http", "ResponseEntity"))
                .build();
        // NOTE: getById here is a stub; idealmente service avrebbe findById e si userebbe quello.

        // POST
        MethodSpec create = MethodSpec.methodBuilder("create" + entitySimple)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(ClassName.get("org.springframework.web.bind.annotation", "PostMapping")).build())
                .addParameter(ParameterSpec.builder(dtoClass, "dto")
                        .addAnnotation(AnnotationSpec.builder(ClassName.get("org.springframework.web.bind.annotation", "RequestBody")).build())
                        .build())
                .returns(ParameterizedTypeName.get(ClassName.get("org.springframework.http", "ResponseEntity"), dtoClass))
                .addStatement("$T created = $N.save(dto)", dtoClass, decap(serviceName))
                .addStatement("return $T.status($T.CREATED).body(created)", ClassName.get("org.springframework.http", "ResponseEntity"), ClassName.get("org.springframework.http", "HttpStatus"))
                .build();
        builder.addMethod(create);

        // PUT
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

        // DELETE
        MethodSpec delete = MethodSpec.methodBuilder("delete" + entitySimple)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(ClassName.get("org.springframework.web.bind.annotation", "DeleteMapping"))
                        .addMember("value", "$S", "/{" + idName + "}")
                        .build())
                .addParameter(ParameterSpec.builder(idType, idName)
                        .addAnnotation(AnnotationSpec.builder(ClassName.get("org.springframework.web.bind.annotation", "PathVariable")).build())
                        .build())
                .addStatement("$N.delete($N)", decap(serviceName), idName)
                .addStatement("return $T.noContent().build()", ClassName.get("org.springframework.http", "ResponseEntity"))
                .returns(ParameterizedTypeName.get(ClassName.get("org.springframework.http", "ResponseEntity"), TypeName.VOID))
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
        return typeUtils.isAssignable(t, elementUtils.getTypeElement(Collection.class.getCanonicalName()).asType())
                || typeUtils.isAssignable(t, elementUtils.getTypeElement(List.class.getCanonicalName()).asType())
                || typeUtils.isAssignable(t, elementUtils.getTypeElement(Set.class.getCanonicalName()).asType());
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String decap(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private String lowerFirst(String s) {
        return decap(s);
    }

    private String pluralize(String s) {
        // semplice pluralizzazione: se finisce con y -> ies, altrimenti aggiungi s
        if (s.endsWith("y") && s.length() > 1) {
            return s.substring(0, s.length() - 1) + "ies";
        }
        if (s.endsWith("s")) {
            return s + "es";
        }
        return s + "s";
    }
}
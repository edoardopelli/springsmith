# SpringSmith CRUD Annotation Processor

**SpringSmith** is an annotation processor that auto-generates basic CRUD scaffolding for JPA `@Entity` classes in a Spring Boot project. From each entity it creates:

- DTO (`XDTO`) with Lombok and `@JsonInclude(Include.NON_NULL)` semantics.
  - Includes scalar fields.
  - For `@ManyToOne` / `@OneToOne` relations: only the related entity's ID (e.g. `authorId`).
  - Omits `@OneToMany` and `@ManyToMany` collections entirely.
- MapStruct mapper interface (`XMapper`) that maps between entity and DTO.
  - Entity → DTO handles extracting related IDs.
  - DTO → Entity ignores complex relations; resolving those (e.g., loading the referenced entity by ID) is left to the service.
- Spring Data JPA repository interface (`XRepository`) extending `JpaRepository<X, ID>`.
- Service class (`XService`) with concrete implementations of:
  - `findAll()`
  - `findById(...)`
  - `save(...)`
  - `update(...)`
  - `delete(...)`
- REST controller (`XController`) exposing CRUD endpoints under `/api/{pluralEntity}` using `ResponseEntity`.

## Key Design Choices

- **Package placement**: Generated classes go into the same base package as the entity, swapping the last segment with the plural of the component type:
  - e.g. entity in `com.foo.bar.entity` yields:
    - DTO in `com.foo.bar.dtos`
    - Mapper in `com.foo.bar.mappers`
    - Repository in `com.foo.bar.repositories`
    - Service in `com.foo.bar.services`
    - Controller in `com.foo.bar.controllers`

- **DTO simplification**: Prevents deep object graphs by excluding collection relations and flattening `ManyToOne`/`OneToOne` to just their foreign key IDs.

- **Extensibility**: Only **basic CRUD** is generated. For more advanced queries, business rules, joins, projections, or custom REST behaviors, you must manually extend:
  - Repository: add custom query methods or `@Query`s.
  - Service: inject other dependencies, resolve relation IDs to entities, add business logic.
  - Controller: add new endpoints, parameterization, validation, error handling, paging, etc.

## Maven Usage

You need two modules/dependencies:

1. **The processor JAR** (e.g., `springsmith-processor`) that contains the annotation processor (`org.cheetah.springsmith.processor.CrudScaffoldingProcessor`).
2. **Your application module** that declares entities and consumes the generated code.

### Example `pom.xml` for the application that *uses* the processor

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
             https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>org.cheetah.springsmith</groupId>
  <artifactId>springsmith-app</artifactId>
  <version>0.1.0</version>
  <packaging>jar</packaging>

  <properties>
    <java.version>17</java.version>
    <spring.boot.version>3.2.0</spring.boot.version>
    <mapstruct.version>1.5.5.Final</mapstruct.version>
    <lombok.version>1.18.28</lombok.version>
    <auto.service.version>1.0.1</auto.service.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-dependencies</artifactId>
        <version>${spring.boot.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <!-- Spring Boot -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>

    <!-- MapStruct API -->
    <dependency>
      <groupId>org.mapstruct</groupId>
      <artifactId>mapstruct</artifactId>
      <version>${mapstruct.version}</version>
    </dependency>

    <!-- Lombok -->
    <dependency>
      <groupId>org.projectlombok</groupId>
      <artifactId>lombok</artifactId>
      <version>${lombok.version}</version>
      <scope>provided</scope>
    </dependency>

    <!-- AutoService annotations (needed if your entities or other code uses it) -->
    <dependency>
      <groupId>com.google.auto.service</groupId>
      <artifactId>auto-service-annotations</artifactId>
      <version>${auto.service.version}</version>
    </dependency>

    <!-- Example runtime DB -->
    <dependency>
      <groupId>com.h2database</groupId>
      <artifactId>h2</artifactId>
      <scope>runtime</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <!-- Spring Boot plugin -->
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>

      <!-- Compiler + annotation processing -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.11.0</version>
        <configuration>
          <source>${java.version}</source>
          <target>${java.version}</target>
          <annotationProcessorPaths>
            <path>
              <groupId>org.mapstruct</groupId>
              <artifactId>mapstruct-processor</artifactId>
              <version>${mapstruct.version}</version>
            </path>
            <path>
              <groupId>org.projectlombok</groupId>
              <artifactId>lombok</artifactId>
              <version>${lombok.version}</version>
            </path>
            <path>
              <groupId>com.google.auto.service</groupId>
              <artifactId>auto-service</artifactId>
              <version>${auto.service.version}</version>
            </path>
            <path>
              <groupId>org.cheetah.springsmith</groupId>
              <artifactId>springsmith-processor</artifactId>
              <version>0.1.0</version>
            </path>
          </annotationProcessorPaths>
          <compilerArgs>
            <arg>-Amapstruct.defaultComponentModel=spring</arg>
          </compilerArgs>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

### Minimal Entity Example

```java
package com.example.domain;

import jakarta.persistence.*;

@Entity
public class Book {
    @Id
    private Long id;
    private String title;

    @ManyToOne
    private Author author;
}
```

From this, the processor will generate:
- `BookDTO` (with `id`, `title`, `authorId`)
- `BookMapper`
- `BookRepository`
- `BookService`
- `BookController` under `/api/books`

## Generated API Behavior

The controller exposes endpoints like:

- `GET /api/books` → list all books
- `GET /api/books/{id}` → get one by ID
- `POST /api/books` → create (accepts `BookDTO`)
- `PUT /api/books/{id}` → update
- `DELETE /api/books/{id}` → delete

DTOs serialize with Jackson, omitting nulls (`@JsonInclude(Include.NON_NULL)`). Lombok reduces boilerplate in DTOs.

## Extension Points

The generated code provides only **basic CRUD**. For real-world needs, you should:

- **Extend repositories** for custom queries:
  ```java
  public interface BookRepository extends JpaRepository<Book, Long> {
      List<Book> findByTitleContaining(String fragment);
  }
  ```

- **Enhance services** to resolve related IDs into entities:
  ```java
  public BookDTO save(BookDTO dto) {
      Book book = bookMapper.toBook(dto);
      if (dto.getAuthorId() != null) {
          Author author = authorRepository.findById(dto.getAuthorId())
              .orElseThrow(...);
          book.setAuthor(author);
      }
      // continue saving...
  }
  ```

- **Add custom controller endpoints** (filtering, paging, batch operations, etc.)

## Requirements

- Java 17+
- Spring Boot (example tested with 3.x)
- MapStruct
- Lombok (IDE support requires Lombok plugin/enabled annotation processing)
- JPA provider (e.g., Hibernate)
- The processor JAR on the annotation processor path

## Caveats

- Pluralization for the REST path is naive (`y` → `ies`, append `s`/`es`); adjust manually if incorrect.
- DTO → Entity mapping ignores complex relations; service must handle relation reconstruction.
- No validation, security, paging, or error wrapping is generated — add those layers as needed.
- Inheritance in entities is not handled; only flat entity classes are supported.

## Development & Packaging

The processor itself lives in package `org.cheetah.springsmith.processor` and must be packaged as a Maven artifact (e.g., `springsmith-processor`) and published to your local or remote Maven repository so consuming projects can reference it in their `annotationProcessorPaths`.

## License & Attribution

(You can add your license here, e.g., MIT, Apache 2.0, or internal company license.)

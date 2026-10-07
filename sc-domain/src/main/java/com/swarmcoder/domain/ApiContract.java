/*
 * Copyright 2026 Franz Schöning
 * Project: https://github.com/SwarmCoder/swarmcoder
 * Author: Franz Schöning - Principal Enterprise Architect (https://www.franzschoning.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.swarmcoder.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One API boundary the design fixes so that several tasks can be built against it at once.
 *
 * <p><b>It is also the run's TYPE VOCABULARY</b> (author decision, 2026-09-03). Until then a
 * contract was a name and a prose sketch, and three parties read it and each chose names freely:
 * the test author invented a type, the worker of the enabler task chose a field, and nothing
 * compared the two. On run 13 the test author wrote an acceptance test against
 * {@code com.swarmcoder.demo.bookshelf.Rating}; two waves delivered a {@code Book} carrying a
 * rating field and a {@code BookService}, and nobody delivered {@code Rating}. The test could
 * never compile, "does not compile" counts as a valid red state at every gate that looked at it,
 * and the run spent three waves reaching a test that was broken from the moment it was written.
 *
 * <p>So a contract now carries the two things that make the vocabulary checkable rather than
 * agreed by hope: {@link #typeName()}, the fully-qualified name of the type, and
 * {@link #members()}, the fields and methods an acceptance test will actually touch. The test
 * author may name only these types (and types already in the checkout); the task told to deliver
 * one is verified against exactly this list.
 *
 * <p>Both are optional and blank-tolerant: a design produced before this existed, or by a model
 * that answered without them, behaves exactly as it did — every check keyed off them does nothing
 * when they are absent, because an absent instrument is not a verdict.
 */
public class ApiContract {
    private UUID id;
    private String name;
    private String description;
    private String signatureSketch;
    /**
     * The fully-qualified name of the type this contract fixes, e.g.
     * {@code com.swarmcoder.demo.bookshelf.Rating}. Null or blank when the design did not state
     * one, and then this contract constrains nobody's vocabulary.
     */
    private String typeName;
    /**
     * The members an acceptance test needs, one per entry, as written in Java: {@code "int rating"}
     * for a field, {@code "String title()"} for a method.
     */
    private List<String> members;

    public ApiContract() {}

    public ApiContract(UUID id, String name, String description, String signatureSketch) {
        this(id, name, description, signatureSketch, null, null);
    }

    public ApiContract(UUID id, String name, String description, String signatureSketch,
                       String typeName, List<String> members) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.signatureSketch = signatureSketch;
        this.typeName = typeName;
        this.members = members == null ? new ArrayList<>() : new ArrayList<>(members);
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String name() { return name; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String description() { return description; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String signatureSketch() { return signatureSketch; }
    public String getSignatureSketch() { return signatureSketch; }
    public void setSignatureSketch(String signatureSketch) { this.signatureSketch = signatureSketch; }
    public String typeName() { return typeName; }
    public String getTypeName() { return typeName; }
    public void setTypeName(String typeName) { this.typeName = typeName; }
    public List<String> members() { return members == null ? List.of() : members; }
    public List<String> getMembers() { return members(); }
    public void setMembers(List<String> members) {
        this.members = members == null ? new ArrayList<>() : new ArrayList<>(members);
    }

    /** True when this contract names a concrete type, which is what every vocabulary check needs. */
    public boolean namesAType() {
        return typeName != null && !typeName.isBlank();
    }

    /** The simple name of {@link #typeName()}, or "" when there is none. */
    public String simpleTypeName() {
        if (!namesAType()) {
            return "";
        }
        String name = typeName.strip();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    /** The package of {@link #typeName()}, or "" for the default package or no type at all. */
    public String packageName() {
        if (!namesAType()) {
            return "";
        }
        String name = typeName.strip();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(0, dot);
    }

    /** One line a person and a model both read the same way: {@code com.x.Book{int rating; }}. */
    public String describe() {
        if (!namesAType()) {
            return name == null ? "" : name;
        }
        StringBuilder sb = new StringBuilder(typeName.strip()).append('{');
        for (String member : members()) {
            if (member != null && !member.isBlank()) {
                sb.append(member.strip()).append("; ");
            }
        }
        return sb.append('}').toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ApiContract that = (ApiContract) o;
        return Objects.equals(this.id, that.id) && Objects.equals(this.name, that.name) && Objects.equals(this.description, that.description) && Objects.equals(this.signatureSketch, that.signatureSketch) && Objects.equals(this.typeName, that.typeName) && Objects.equals(this.members(), that.members());
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, description, signatureSketch, typeName, members());
    }
}

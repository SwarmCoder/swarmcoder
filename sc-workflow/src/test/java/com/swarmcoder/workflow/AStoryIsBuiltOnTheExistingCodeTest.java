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
package com.swarmcoder.workflow;

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.DesignDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live run 68, 2026-10-02, in its shape: a second story on a repository that already holds the
 * first story's service interface, data types and store root. The design duplicated them in a new
 * package and passed review; the test author then guessed a package for the existing store root
 * and the run parked.
 */
class AStoryIsBuiltOnTheExistingCodeTest {

    @TempDir
    Path checkout;

    @BeforeEach
    void aRepositoryThatHoldsTheFirstStory() throws IOException {
        write("app-shared/src/main/java/com/hamlog/Contact.java", """
            package com.hamlog;
            public record Contact(String callsign, java.time.Instant start) {}
            """);
        write("app-shared/src/main/java/com/hamlog/LogService.java", """
            package com.hamlog;
            import java.util.List;
            public interface LogService {
                List<Contact> list();
                void softDelete(String id);
            }
            """);
        write("app-shared/src/main/java/com/hamlog/CardState.java", """
            package com.hamlog;
            public enum CardState { NONE, SENT, RECEIVED }
            """);
        write("app-server/src/main/java/com/hamlog/server/HamRoot.java", """
            package com.hamlog.server;
            public class HamRoot {
                public HamRoot() {}
                public java.util.List<com.hamlog.Contact> contacts() { return null; }
                private void hidden() {}
                public static class Builder {}
            }
            """);
        write("app-server/src/main/java/com/hamlog/server/Unrelated.java", """
            package com.hamlog.server;
            public class Unrelated { public void go() {} }
            """);
        write("app-server/src/test/java/swarm/accept/EditTest.java", """
            package swarm.accept;
            import com.hamlog.server.HamRoot;
            class EditTest { void edits() { new HamRoot(); } }
            """);
    }

    @Test
    void theInventoryNamesEachExistingTypeWithItsKindModuleAndPublicMembers() {
        String inventory = ExistingProjectTypes.of(checkout)
            .inventory("Add a contact by hand", 6_000);

        assertThat(inventory)
            .contains("com.hamlog.Contact (record, module app-shared): String callsign(); "
                + "java.time.Instant start()")
            .contains("com.hamlog.LogService (interface, module app-shared): List<Contact> list(); "
                + "void softDelete(String id)")
            .contains("com.hamlog.CardState (enum, module app-shared): NONE; SENT; RECEIVED")
            .contains("com.hamlog.server.HamRoot (class, module app-server): HamRoot(); ")
            .doesNotContain("hidden")
            .doesNotContain("Builder (")       // a nested type is not something a story extends
            .doesNotContain("swarm.accept");   // nor is a test
    }

    @Test
    void theMostRelevantTypesComeFirstAndTheRestAreStillNamedWhenTheRoomRunsOut() {
        ExistingProjectTypes existing = ExistingProjectTypes.of(checkout);

        String inventory = existing.inventory("Add a contact by hand; the Contact is listed", 6_000);
        // named outright, then used by an earlier story's test, then the rest by name
        assertThat(inventory.indexOf("com.hamlog.Contact ("))
            .isLessThan(inventory.indexOf("com.hamlog.server.HamRoot ("));
        assertThat(inventory.indexOf("com.hamlog.server.HamRoot ("))
            .isLessThan(inventory.indexOf("com.hamlog.server.Unrelated ("));

        String small = existing.inventory("Add a contact by hand; the Contact is listed", 120);
        assertThat(small).contains("com.hamlog.Contact (record")
            .contains("Also exist (members not shown): ")
            .contains("com.hamlog.server.Unrelated")
            .doesNotContain("Unrelated (class");
    }

    @Test
    void anEmptyRepositoryGivesNoInventoryAndNoObjection(@TempDir Path empty) {
        ExistingProjectTypes none = ExistingProjectTypes.of(empty);

        assertThat(none.architectBrief("anything", 6_000)).isEmpty();
        assertThat(none.testAuthorBrief("anything", 6_000)).isEmpty();
        assertThat(none.duplicateObjections(design(contract("com.hamlog.Contact")))).isEmpty();
    }

    @Test
    void theArchitectIsToldToExtendAnExistingTypeWhereItIs() {
        assertThat(ExistingProjectTypes.of(checkout).architectBrief("Add a contact", 6_000))
            .contains("TYPES THIS PROJECT ALREADY HAS")
            .contains("com.hamlog.LogService (interface")
            .contains("EXISTING name and package")
            .contains("only the members this story ADDS");
    }

    @Test
    void aContractThatDuplicatesAnExistingTypeInAnotherPackageIsSentBackNamingTheRealOne() {
        DesignDocument design = design(
            contract("com.hamlog.shared.LogService", "void add(Contact c)", "List<Contact> list()"),
            contract("com.hamlog.shared.Contact", "String callsign()"),
            contract("com.hamlog.shared.CardState", "NONE"),
            contract("com.hamlog.shared.AddContactRequest", "String callsign()"));

        List<String> objections = ExistingProjectTypes.of(checkout).duplicateObjections(design);

        assertThat(objections).hasSize(3);
        assertThat(objections.get(0))
            .contains("the contract com.hamlog.shared.LogService duplicates a type this project "
                + "already has")
            .contains("com.hamlog.LogService already exists in app-shared (interface with "
                + "List<Contact> list(); void softDelete(String id))")
            .contains("Extend it there, do not create com.hamlog.shared.LogService")
            .contains("name the contract com.hamlog.LogService");
        assertThat(objections.get(1)).contains("com.hamlog.Contact already exists in app-shared");
        assertThat(objections.get(2)).contains("com.hamlog.CardState already exists");
    }

    @Test
    void aContractForAnExistingTypeInItsRealPackageMeansAddTheseMembersAndIsLeftAlone() {
        DesignDocument design = design(
            contract("com.hamlog.LogService", "void add(Contact c)"),
            contract("com.hamlog.client.AddContactForm", "void submit()"),
            contract("swarm.accept.Contact", "void aTestClass()"),
            contract("com.hamlog.client.Builder", "void build()"));

        assertThat(ExistingProjectTypes.of(checkout).duplicateObjections(design)).isEmpty();
    }

    @Test
    void aTestNamingAnExistingTypeInTheWrongPackageIsToldWhereTheRealOneIs() throws IOException {
        String path = writeTest("com.hamlog.server.store.HamRoot");
        DesignDocument design = design(contract("com.hamlog.server.store.Journal", "void add()"));

        AcceptanceTestVocabulary.Check check =
            AcceptanceTestVocabulary.check(checkout, design, List.of(path));

        assertThat(check.unknowns()).hasSize(1);
        assertThat(check.unknowns().get(0).realTypes()).containsExactly("com.hamlog.server.HamRoot");
        assertThat(AcceptanceTestVocabulary.reask(check))
            .contains("com.hamlog.server.store.HamRoot does not exist; com.hamlog.server.HamRoot "
                + "does. Use that name.");
        assertThat(AcceptanceTestVocabulary.brief("the task", check))
            .contains("a type of that name does exist: com.hamlog.server.HamRoot");
    }

    @Test
    void aWrongPackageWithExactlyOneRealTypeOfThatNameIsCorrectedInTheTestWithoutAModelCall()
            throws IOException {
        String path = writeTest("com.hamlog.server.store.HamRoot");
        DesignDocument design = design(contract("com.hamlog.server.store.Journal", "void add()"));
        AcceptanceTestVocabulary.Check check =
            AcceptanceTestVocabulary.check(checkout, design, List.of(path));

        List<String> corrected = AcceptanceTestVocabulary.correctMisplacedNames(checkout, check);

        assertThat(corrected).containsExactly(
            "com.hamlog.server.store.HamRoot -> com.hamlog.server.HamRoot (in " + path + ")");
        assertThat(Files.readString(checkout.resolve(path)))
            .contains("import com.hamlog.server.HamRoot;")
            .doesNotContain("server.store.HamRoot");
        assertThat(AcceptanceTestVocabulary.check(checkout, design, List.of(path)).ok()).isTrue();
    }

    @Test
    void aNameWithTwoRealTypesOrNoneIsLeftForTheAuthor() throws IOException {
        write("app-client/src/main/java/com/hamlog/client/HamRoot.java",
            "package com.hamlog.client;\npublic class HamRoot {}\n");
        String path = writeTest("com.hamlog.server.store.HamRoot");
        DesignDocument design = design(contract("com.hamlog.server.store.Journal", "void add()"));
        AcceptanceTestVocabulary.Check check =
            AcceptanceTestVocabulary.check(checkout, design, List.of(path));

        assertThat(check.unknowns().get(0).realTypes()).hasSize(2);
        assertThat(AcceptanceTestVocabulary.correctMisplacedNames(checkout, check)).isEmpty();
        assertThat(Files.readString(checkout.resolve(path))).contains("server.store.HamRoot");
    }

    private String writeTest(String importedRoot) throws IOException {
        String path = "app-server/src/test/java/swarm/accept/AddTest.java";
        write(path, "package swarm.accept;\nimport " + importedRoot + ";\n"
            + "import com.hamlog.server.store.Journal;\n"
            + "class AddTest { void adds() { HamRoot root = new HamRoot(); new Journal().add(); } }\n");
        return path;
    }

    private static ApiContract contract(String type, String... members) {
        return new ApiContract(UUID.randomUUID(), type, "", "", type, List.of(members));
    }

    private static DesignDocument design(ApiContract... contracts) {
        return new DesignDocument(UUID.randomUUID(), 1, "goal", List.of(), List.of(),
            List.of(contracts), List.of(), null, Instant.now());
    }

    private void write(String relative, String content) throws IOException {
        Path file = checkout.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}

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
import com.swarmcoder.knowledge.LibraryTypes;
import com.swarmcoder.knowledge.ProjectTypes;
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
 * Live run 67, 2026-10-02, in its shape: a design that passed review with contracts in the
 * reference library's packages — two types the library never had, one that is in another package
 * of it, one that exists exactly as named — over a project whose own code is in a different
 * namespace. The planner was then rejected six times for not delivering them.
 */
class ContractsAreDeliverableTest {

    @TempDir
    Path reference;

    @TempDir
    Path checkout;

    private LibraryTypes library;
    private ProjectTypes project;

    @BeforeEach
    void aProjectAndAMiniatureLibrary() throws IOException {
        write(reference.resolve("lib-ui/src/main/java/com/widgetlib/ui/component/TextField.java"),
            """
            package com.widgetlib.ui.component;
            public class TextField {
                public String getValue() { return ""; }
                public void setValue(String value) {}
            }
            """);
        write(reference.resolve("lib-server/src/main/java/com/widgetlib/server/VisibleException.java"),
            """
            package com.widgetlib.server;
            public class VisibleException extends RuntimeException {
                public VisibleException(String message) { super(message); }
            }
            """);
        write(reference.resolve("lib-server/src/main/java/com/widgetlib/server/Clock.java"),
            """
            package com.widgetlib.server;
            public final class Clock {
                public long now() { return 0; }
            }
            """);
        write(checkout.resolve("app-shared/src/main/java/com/hamlog/shared/Contact.java"),
            "package com.hamlog.shared;\npublic class Contact {}\n");
        library = LibraryTypes.of(List.of(reference));
        project = ProjectTypes.of(checkout);
    }

    @Test
    void aTypeTheLibraryNeverHadInItsPackageIsSentBackWithTheRealOneNamed() {
        DesignDocument design = design(
            contract("com.widgetlib.ui.TextField", "String getValue()", "void setValue(String value)"),
            contract("com.widgetlib.ui.WithLabel", "TextField textField()"),
            contract("com.hamlog.client.AddContactForm", "void submit()"));

        ContractsAreDeliverable.Outcome outcome =
            ContractsAreDeliverable.check(design, project, library);

        assertThat(outcome.existing()).isEmpty();
        assertThat(outcome.objections()).hasSize(2);
        assertThat(outcome.objections().get(0))
            .contains("the contract com.widgetlib.ui.TextField cannot be delivered")
            .contains("no task can create a type there")
            .contains("com.widgetlib.ui.TextField does not exist: the library has "
                + "com.widgetlib.ui.component.TextField")
            .contains("Remove this contract and use com.widgetlib.ui.component.TextField");
        assertThat(outcome.objections().get(1))
            .contains("the contract com.widgetlib.ui.WithLabel cannot be delivered")
            .contains("nothing of that name anywhere")
            .contains("define the type in one of this project's own packages (com.hamlog)");
    }

    @Test
    void aTypeTheLibraryHasIsTakenOutOfTheContractsAndNotObjectedTo() {
        ApiContract exception = contract("com.widgetlib.server.VisibleException",
            "VisibleException(String message)", "String getMessage()");
        ApiContract own = contract("com.hamlog.shared.LogbookService", "void add(Contact c)");
        DesignDocument design = design(exception, own);

        ContractsAreDeliverable.Outcome outcome =
            ContractsAreDeliverable.check(design, project, library);

        assertThat(outcome.objections())
            .as("getMessage() is inherited: a member not in the file is not a member not there")
            .isEmpty();
        assertThat(outcome.existing()).containsExactly(exception);
        assertThat(outcome.remaining(design)).containsExactly(own);
    }

    @Test
    void membersTheRealTypeDoesNotHaveAreObjectedToWithItsRealOnes() {
        DesignDocument design = design(contract("com.widgetlib.server.Clock",
            "long now()", "java.time.Instant today()"));

        ContractsAreDeliverable.Outcome outcome =
            ContractsAreDeliverable.check(design, project, library);

        assertThat(outcome.existing()).isEmpty();
        assertThat(outcome.objections()).singleElement().asString()
            .contains("com.widgetlib.server.Clock names a type that already exists")
            .contains("has no java.time.Instant today()")
            .contains("Its real members are: ")
            .contains("long now()");
    }

    @Test
    void theProjectsOwnPackagesTheAcceptanceTestAndUnqualifiedNamesAreLeftAlone() {
        DesignDocument design = design(
            contract("com.hamlog.shared.Contact", "String callsign()"),
            contract("com.hamlog.client.i18n.AddContactTexts", "static final String CALLSIGN"),
            contract("com.hamlog.brandnew.Thing", "void go()"),
            contract("swarm.accept.AddContactTest", "void addsAContact()"),
            contract("Contact", "String callsign()"));

        assertThat(ContractsAreDeliverable.check(design, project, library).clean()).isTrue();
    }

    @Test
    void aJdkTypeIsExistingAndAPackageNobodyKnowsIsNotTheProjects() {
        ApiContract jdk = contract("java.util.List", "int size()");
        DesignDocument design = design(jdk,
            contract("java.util.Shelf", "int size()"),
            contract("org.elsewhere.Gadget", "void go()"));

        ContractsAreDeliverable.Outcome outcome =
            ContractsAreDeliverable.check(design, project, library);

        assertThat(outcome.existing()).containsExactly(jdk);
        assertThat(outcome.objections()).hasSize(2);
        assertThat(outcome.objections().get(0)).contains("the JDK has no Shelf in java.util");
        assertThat(outcome.objections().get(1))
            .contains("org.elsewhere is not a package of this project (its own code is in "
                + "com.hamlog)");
    }

    @Test
    void anEmptyCheckoutJudgesOnlyWhatALibraryOrTheJdkAnswersFor(@TempDir Path empty) {
        DesignDocument design = design(contract("org.elsewhere.Gadget", "void go()"),
            contract("com.newapp.Main", "void run()"));

        assertThat(ContractsAreDeliverable.check(design, ProjectTypes.of(empty), library).clean())
            .isTrue();
    }

    /**
     * 2026-10-04: a library with no reference checkout is still on the classpath as a jar, and
     * the Java language server reads jars. A contract for one of its types is an existing type;
     * one that gives it a member it does not have is sent back with the real members named.
     */
    @Test
    void aTypeThatExistsOnlyInALibraryJarIsCheckedAgainstItsRealMembers() {
        LibraryTypes withJars = library.withJarMembers(name -> name.equals("org.ledger.Ledger")
            ? List.of("post", "balance", "close") : List.of());
        DesignDocument design = design(
            contract("org.ledger.Ledger", "void post(Entry entry)", "long balance()"),
            contract("com.hamlog.shared.Log", "void add(Contact contact)"));
        DesignDocument invented = design(
            contract("org.ledger.Ledger", "void post(Entry entry)", "Money settleAll(int year)"));

        ContractsAreDeliverable.Outcome real =
            ContractsAreDeliverable.check(design, project, withJars);
        ContractsAreDeliverable.Outcome wrong =
            ContractsAreDeliverable.check(invented, project, withJars);

        assertThat(real.objections()).isEmpty();
        assertThat(real.existing()).extracting(ApiContract::typeName)
            .containsExactly("org.ledger.Ledger");
        assertThat(wrong.existing()).isEmpty();
        assertThat(wrong.objections()).hasSize(1);
        assertThat(wrong.objections().get(0))
            .contains("already exists in a library jar this project compiles against")
            .contains("The real Ledger has no settleAll")
            .contains("Its real members are: post, balance, close.");
        // Without a language server nothing changes: the package is simply not the project's.
        assertThat(ContractsAreDeliverable.check(invented, project, library).objections().get(0))
            .contains("org.ledger is not a package of this project");
    }

    private static ApiContract contract(String type, String... members) {
        return new ApiContract(UUID.randomUUID(), type, "", "", type, List.of(members));
    }

    private static DesignDocument design(ApiContract... contracts) {
        return new DesignDocument(UUID.randomUUID(), 1, "goal", List.of(), List.of(),
            List.of(contracts), List.of(), null, Instant.now());
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}

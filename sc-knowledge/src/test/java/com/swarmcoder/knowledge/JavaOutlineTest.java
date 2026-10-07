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
package com.swarmcoder.knowledge;

import com.swarmcoder.testsupport.LocalCheckouts;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The outline scanner against real files from the reference folder this was built for — the
 * shapes a worker actually asks about, not invented ones: a component class with overloaded
 * constructors and a lambda in a body, an enum with javadoc between its constants, a record, an
 * interface with a default method, a text block, a nested enum, a nested interface.
 */
class JavaOutlineTest {

    static final Path ZEROZ4J = LocalCheckouts.find("zeroz4j");
    static final Path UI = ZEROZ4J.resolve("zerozstack-ui-components/src/main/java/com/zeroz4j/ui/component");

    private static JavaOutline outline(Path file) throws Exception {
        assumeTrue(Files.isRegularFile(file), "reference folder not present: " + file);
        return JavaOutline.of(Files.readString(file));
    }

    private static JavaOutline.Member named(JavaOutline.Member type, String name) {
        return type.children().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void aComponentClassYieldsItsPackageImportsAndEveryMember() throws Exception {
        JavaOutline outline = outline(UI.resolve("Button.java"));

        assertThat(outline.packageName).isEqualTo("com.zeroz4j.ui.component");
        assertThat(outline.imports).hasSize(6).contains("com.zeroz4j.signals.Effect");
        assertThat(outline.withoutLicense).doesNotContain("Apache License").startsWith("package ");

        JavaOutline.Member button = outline.primaryType();
        assertThat(button.kind()).isEqualTo(JavaOutline.Kind.TYPE);
        assertThat(button.name()).isEqualTo("Button");
        assertThat(button.header()).startsWith("public class Button extends Component implements HasText");
        assertThat(button.children()).extracting(JavaOutline.Member::name)
            .containsExactly("Button", "Button", "Button", "Button", "Button",
                "withAriaLabel", "getComponent", "addClickListener", "bindEnabled", "getThemePrefix");
        assertThat(button.children().stream().filter(m -> m.kind() == JavaOutline.Kind.CONSTRUCTOR))
            .hasSize(5);
        assertThat(button.children()).allMatch(JavaOutline.Member::exposed);

        JavaOutline.Member deprecated = button.children().get(3);
        assertThat(deprecated.header()).isEqualTo("@Deprecated public Button(Component icon)");
        assertThat(deprecated.summary()).isEqualTo("A button that is nothing but a picture.");

        JavaOutline.Member listener = named(button, "addClickListener");
        assertThat(listener.header())
            .isEqualTo("public DomListenerRegistration addClickListener(EventListener<ClickEvent<Button>> listener)");
        assertThat(listener.text())
            .contains("evt -> {")
            .contains("return addDomEventListener(\"click\", domListener);")
            .endsWith("}");
        assertThat(listener.hasBody()).isTrue();
    }

    @Test
    void anEnumWithJavadocBetweenItsConstantsListsTheConstantsAsCode() throws Exception {
        JavaOutline outline = outline(ZEROZ4J.resolve(
            "zerozstack-store-eclipsestore/src/main/java/com/zeroz4j/store/eclipsestore/StoreMode.java"));

        JavaOutline.Member mode = outline.primaryType();
        assertThat(mode.header()).isEqualTo("public enum StoreMode");
        assertThat(mode.children()).hasSize(1);
        JavaOutline.Member constants = mode.children().get(0);
        assertThat(constants.kind()).isEqualTo(JavaOutline.Kind.ENUM_CONSTANTS);
        assertThat(constants.header()).isEqualTo("EMBEDDED, AUTO_SERVER, CLIENT");
        assertThat(constants.exposed()).isTrue();
    }

    @Test
    void aRecordIsATypeWhoseHeaderCarriesItsComponents() throws Exception {
        JavaOutline outline = outline(ZEROZ4J.resolve("zerozstack-examples/payments-datamodels/"
            + "payments-datamodels-shared/src/main/java/com/zeroz4j/example/payments/model/BankTransfer.java"));

        JavaOutline.Member record = outline.primaryType();
        assertThat(record.kind()).isEqualTo(JavaOutline.Kind.TYPE);
        assertThat(record.name()).isEqualTo("BankTransfer");
        assertThat(record.header())
            .isEqualTo("@DataModel public record BankTransfer(String reference) implements PaymentMethod");
        assertThat(record.children()).isEmpty();
        assertThat(SourceShaper.supertypes(record.header())).containsExactly("PaymentMethod");
    }

    @Test
    void anInterfaceExposesItsAbstractAndDefaultMethods() throws Exception {
        JavaOutline outline = outline(ZEROZ4J.resolve(
            "zerozstack-client/src/main/java/com/zeroz4j/client/router/RouteLayout.java"));

        JavaOutline.Member layout = outline.primaryType();
        assertThat(layout.header()).isEqualTo("public interface RouteLayout<T>");
        JavaOutline.Member load = named(layout, "load");
        JavaOutline.Member render = named(layout, "render");
        assertThat(load.exposed()).isTrue();
        assertThat(load.hasBody()).isTrue();
        assertThat(render.exposed()).isTrue();
        assertThat(render.hasBody()).isFalse();
        assertThat(SourceShaper.signature(render))
            .isEqualTo("Component render(T data, RouteParams params, Component child);");
        assertThat(SourceShaper.signature(load)).isEqualTo("default T load(RouteParams params) { … }");
    }

    @Test
    void aTextBlockDoesNotDerailTheScanner() throws Exception {
        JavaOutline outline = outline(ZEROZ4J.resolve("zerozstack-examples/components-showcase/"
            + "showcase-server/src/main/java/com/zeroz4j/example/server/UserServiceImpl.java"));

        JavaOutline.Member service = outline.primaryType();
        JavaOutline.Member diagnostics = named(service, "systemDiagnostics");
        assertThat(diagnostics.text())
            .contains("--- zeroz4j System Diagnostics ---")
            .contains(".formatted(category, scanDepth);")
            .endsWith("}");
        // The member after a text block — there is none here — and the type itself both close.
        assertThat(service.children().get(service.children().size() - 1)).isSameAs(diagnostics);
    }

    @Test
    void nestedTypesAreMembersWithMembersOfTheirOwn() throws Exception {
        JavaOutline channel = outline(ZEROZ4J.resolve(
            "zerozstack-client/src/main/java/com/zeroz4j/client/WasmRmiClientChannel.java"));
        JavaOutline.Member state = named(channel.primaryType(), "State");
        assertThat(state.kind()).isEqualTo(JavaOutline.Kind.TYPE);
        assertThat(state.header()).isEqualTo("public enum State");
        assertThat(state.children().get(0).header()).isEqualTo("CONNECTING, CONNECTED, RECONNECTING, CLOSED");

        JavaOutline upload = outline(UI.resolve("FileUpload.java"));
        JavaOutline.Member fileUpload = upload.primaryType();
        JavaOutline.Member listener = named(fileUpload, "UploadListener");
        assertThat(listener.kind()).isEqualTo(JavaOutline.Kind.TYPE);
        assertThat(listener.header()).isEqualTo("@FunctionalInterface public interface UploadListener");
        assertThat(listener.children()).extracting(JavaOutline.Member::name).containsExactly("onUploadFinished");
        assertThat(listener.children().get(0).exposed()).isTrue();
        // The public surface of a 637-line class is eight declarations; the other 30 are private.
        List<JavaOutline.Member> exposed = fileUpload.children().stream()
            .filter(JavaOutline.Member::exposed).toList();
        assertThat(exposed).extracting(JavaOutline.Member::name).containsExactly("UploadListener",
            "FileUpload", "setTitle", "setSubtitle", "setAccept", "setMultiple", "addUploadListener",
            "clearFinished");
        assertThat(fileUpload.children().size() - exposed.size()).isEqualTo(30);
    }

    @Test
    void javadocSummariesAreOneSentenceWithTagsAndMarkupRemoved() {
        assertThat(JavaOutline.firstSentence("""
            /**
             * Sets the grey example text shown while the field is empty.
             *
             * @param placeholder the placeholder text, or null to remove it
             */""")).isEqualTo("Sets the grey example text shown while the field is empty.");
        assertThat(JavaOutline.firstSentence("/** {@link #setAriaLabel(String)}, returning the button. */"))
            .isEqualTo("setAriaLabel(String), returning the button.");
        assertThat(JavaOutline.firstSentence("/** <p>Told when one file has finished, whether it was kept or not.</p> */"))
            .isEqualTo("Told when one file has finished, whether it was kept or not.");
    }
}

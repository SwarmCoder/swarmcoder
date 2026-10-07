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
package com.swarmcoder.console.ui;

import com.swarmcoder.console.api.ControlService;
import com.swarmcoder.console.api.ControlService_Stub;
import com.zeroz4j.ui.component.Button;
import com.zeroz4j.ui.component.Card;
import com.zeroz4j.ui.component.CardTitle;
import com.zeroz4j.ui.component.Checkbox;
import com.zeroz4j.ui.component.TextArea;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.HorizontalLayout;
import com.zeroz4j.signals.Effect;
import com.zeroz4j.signals.ValueSignal;

/**
 * Settings (design §5.5): edit the config YAML directly — the same file the TUI edits, so
 * this is honest full parity. Save validates server-side; a restart applies the wiring.
 *
 * <p>It also carries the <b>developer section</b> (author decision 2026-07-27). Prompt Lab and the
 * component gallery are tools for people building SwarmCoder, not steps an operator takes, and
 * leaving them in the navigation is part of what made the shell read as a pile of unrelated things.
 * They live behind one clearly-labelled checkbox here, persisted client-side exactly the way the
 * theme is — this is a preference about one browser, not a fact about the project.
 */
final class SettingsView extends Card {

    private final ControlService control = new ControlService_Stub();
    private final ValueSignal<String> status = new ValueSignal<>("");

    private final TextArea editor = new TextArea("");

    SettingsView() {
        add(developerSection());

        HorizontalLayout header = new HorizontalLayout();
        header.addClassName("items-center");
        header.addClassName("gap-2");
        header.add(new CardTitle("Settings (config.yaml)"));
        Button reload = new Button("Reload");
        reload.addClassName("btn-xs");
        reload.addClickListener(e -> load());
        Button save = new Button("Save");
        save.addClassName("btn-xs");
        save.addClassName("btn-primary");
        save.addClickListener(e -> save());
        header.add(reload, save);
        add(header);

        editor.addClassName("w-full");
        editor.addClassName("h-96");
        editor.addClassName("font-mono");
        editor.addClassName("text-xs");
        add(editor);

        // setText, NOT setInnerHTML. The status is a sentence the server composed, and putting it
        // in as markup meant any "<" in it silently ate the rest of the line, and anything that
        // reached it from a file the operator did not write ran as HTML.
        Div statusLine = new Div();
        statusLine.addClassName("text-sm");
        statusLine.addClassName("mt-1");
        Effect.create(() -> statusLine.setText(status.get()));
        add(statusLine);

        load();
    }

    /**
     * The one switch that puts Prompt Lab and Components back on the stage bar (and in the ⌘K
     * palette). Labelled with what it does and who it is for, because a checkbox called "developer
     * mode" that silently adds two entries somewhere else is a worse discoverability problem than
     * the menu it was meant to tidy.
     */
    private Div developerSection() {
        Div section = new Div();
        section.addClassName("mb-4 rounded-lg border border-base-300 p-3");

        Div heading = new Div("Developer");
        heading.addClassName("text-sm font-semibold");
        Div hint = new Div("Prompt Lab (the exact prompt each worker received) and the component "
            + "gallery are tools for building SwarmCoder, not steps in the process. Off by default "
            + "so the stage bar shows only what an operator has to do.");
        hint.addClassName("text-xs text-base-content/60 leading-relaxed mt-1 mb-2");
        section.add(heading, hint);

        Div row = new Div();
        row.addClassName("flex items-center gap-2");
        Checkbox toggle = new Checkbox();
        toggle.addClassName("checkbox checkbox-sm");
        toggle.getElement().setAttribute("data-testid", "devtools-toggle");
        toggle.setValue(DevTools.isEnabled());
        // Purely client-side — no RMI, so this is safe from a value-change listener.
        toggle.addValueChangeListener(e -> DevTools.set(Boolean.TRUE.equals(e.getValue())));
        Div label = new Div("Show developer tools (Prompt Lab, Components)");
        label.addClassName("text-sm");
        row.add(toggle, label);
        section.add(row);
        return section;
    }

    private void load() {
        
            try {
                editor.setValue(control.settingsYaml());
                status.set("Loaded. Edits apply after restart.");
            } catch (Exception ex) {
                status.set("Load failed: " + ex.getMessage());
                // The editor keeps whatever was in it, which after a failed reload is stale text
                // that Save would happily write over the real config.yaml.
                ClientLog.error("SettingsView", "config.yaml did not load — the editor is not "
                    + "showing what is on disk: " + ex);
            }
    }

    private void save() {
        String yaml = editor.getValue();
        
            try {
                String error = control.saveSettingsYaml(yaml);
                status.set(error.isEmpty() ? "Saved. Restart SwarmCoder to apply." : error);
                if (!error.isEmpty()) {
                    // A rejected YAML leaves the file untouched, which is the same outcome as a
                    // thrown save — the operator's edits exist only in this textarea.
                    ClientLog.error("SettingsView", "config.yaml save refused — the edits are only "
                        + "in the browser and config.yaml is unchanged: " + error);
                }
            } catch (Exception ex) {
                status.set("Save failed: " + ex.getMessage());
                ClientLog.error("SettingsView", "config.yaml save failed — the edits are only in "
                    + "the browser and config.yaml is unchanged: " + ex);
            }
    }
}

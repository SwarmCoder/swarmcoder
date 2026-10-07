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

import com.swarmcoder.domain.Task;
import com.swarmcoder.verify.TypeDeliverability;

import java.util.ArrayList;
import java.util.List;

/**
 * The concrete implementation types a plan's own tasks promise to write, read off their write
 * sets rather than off the design's contracts.
 *
 * <h2>The run this exists because of</h2>
 *
 * <p>Harness run 38, 2026-09-25. The design fixed four contracts — {@code Book}, {@code Rating},
 * {@code BookshelfService}, {@code BookshelfStore} — all interfaces or plain data. The plan's task
 * 5, "Implement BookshelfServiceImpl coordinating service and store", had the write set entry
 * {@code bookshelf-demo-server/src/main/java/com/swarmcoder/demo/bookshelf/server/
 * BookshelfServiceImpl.java}. The test author, told correctly that a test may not implement the
 * {@code BookshelfService} contract itself (see {@link SelfImplementedContract}), tried the only
 * other thing it could think of: naming the concrete class task 5 was about to write,
 * {@code com.swarmcoder.demo.bookshelf.server.BookshelfServiceImpl}. {@link
 * AcceptanceTestVocabulary}, which at the time knew only the design's contracts and the checkout's
 * existing types, refused it — "nothing in this plan delivers" — even though task 5's write set
 * says, in as many words, that it does. The test author had no legal way left to obtain a real
 * implementation: name the interface and be refused for standing in for it, or name the
 * implementation and be refused for inventing it.
 *
 * <h2>What this reads</h2>
 *
 * <p>A task's write set is the plan's own promise about what it will create — it is what {@link
 * com.swarmcoder.verify.TypeDeliverability} already asks, in the other direction, to tell a
 * healthy "does not compile yet" from a broken one. This class asks the forward question for one
 * write-set entry at a time: which concrete type, if any, does this FILE entry name? A directory
 * entry answers nothing here (it promises a whole package, not one class); a file entry under a
 * recognised source root answers with the fully-qualified type its path and file name spell out.
 *
 * <p>The result is offered to a test author alongside the design's own contracts: a class a task's
 * write set names is exactly as real, for vocabulary purposes, as one the design fixed — the plan
 * is going to create it either way.
 */
public final class PlannedImplementations {

    private PlannedImplementations() {}

    /** One concrete type a task's write set names. */
    public record Planned(String typeName, String simpleName, String taskTitle) {
        public String describe() {
            return "`" + simpleName + "` (`" + typeName + "`) — written by task '" + taskTitle
                + "'";
        }
    }

    /**
     * Every concretely-named type in {@code tasks}' write sets, in task order. A write-set entry
     * that names no single type (a directory, or a file under no recognisable source root) simply
     * contributes nothing — see {@link TypeDeliverability#typeNamed}, which fails closed rather
     * than guessing a package for a name about to be handed to a test author as safe to use.
     */
    public static List<Planned> of(List<Task> tasks) {
        List<Planned> planned = new ArrayList<>();
        if (tasks == null) {
            return planned;
        }
        for (Task task : tasks) {
            if (task == null || task.writeSet() == null) {
                continue;
            }
            for (String entry : task.writeSet()) {
                String typeName = TypeDeliverability.typeNamed(entry);
                if (typeName == null) {
                    continue;
                }
                int dot = typeName.lastIndexOf('.');
                String simple = dot < 0 ? typeName : typeName.substring(dot + 1);
                planned.add(new Planned(typeName, simple, task.title()));
            }
        }
        return planned;
    }
}

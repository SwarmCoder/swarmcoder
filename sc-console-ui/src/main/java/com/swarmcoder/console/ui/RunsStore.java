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

import com.swarmcoder.console.api.RunSummaryDto;
import com.zeroz4j.signals.ValueSignal;

import java.util.ArrayList;
import java.util.List;

/** The sidebar runs list; refreshed via RMI and nudged when run-graph pushes arrive. */
final class RunsStore {

    static final ValueSignal<List<RunSummaryDto>> runs = new ValueSignal<>(new ArrayList<>());

    private RunsStore() {}
}

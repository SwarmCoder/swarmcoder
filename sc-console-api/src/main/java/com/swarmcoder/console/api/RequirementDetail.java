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
package com.swarmcoder.console.api;

import java.util.List;

/**
 * One requirement in full, as the Console's Requirements view shows it. Not a wire model.
 *
 * @param row        the requirement's line
 * @param text       the full requirement statement
 * @param category   its grouping, or an empty string
 * @param source     the uploaded document it was drawn from, or an empty string
 * @param reason     for a requirement raised by a model role, the reason that role gave; empty
 *                   when none was recorded
 * @param checks     every check, one line each: "R3:C1 [accepted/unverified] text - test: ref"
 * @param dependents the handles that wait for this one
 * @param history    who changed what and when, oldest first, one line each
 */
public record RequirementDetail(RequirementRow row, String text, String category, String source,
                                String reason, List<String> checks, List<String> dependents,
                                List<String> history) {
}

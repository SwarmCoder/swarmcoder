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
 * One requirement as a single short line, for the supervisor's listing. Not a wire model.
 *
 * @param handle    the requirement's label, such as R11
 * @param title     its short name
 * @param state     draft, agreed, delivered or retired
 * @param kind      functional or non-functional
 * @param priority  its priority, or an empty string
 * @param checks    how many checks it carries
 * @param dependsOn the handles it waits for
 * @param draftedBy who created it and, when recorded, from which story ("architect, while
 *                  designing S3"); empty when nothing was recorded
 * @param draftedAt when it was created, or an empty string
 */
public record RequirementRow(String handle, String title, String state, String kind,
                             String priority, int checks, List<String> dependsOn,
                             String draftedBy, String draftedAt) {
}

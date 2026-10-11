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

/**
 * One read-only reference entry of a project: the source checkout of a library it builds on, or a
 * documentation site. Not a wire model, so a plain record.
 *
 * @param name   what roles call it: the folder's own name (its last path segment), or the address
 * @param path   the folder or the site address, as stored in the project's settings
 * @param kind   {@code folder} or {@code site}
 * @param exists whether a folder is there on this machine now (always true for a site)
 */
public record ContextFolder(String name, String path, String kind, boolean exists) {
}

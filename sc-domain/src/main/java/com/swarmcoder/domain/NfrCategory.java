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

/**
 * The quality attribute a {@link RequirementKind#NON_FUNCTIONAL} requirement constrains.
 * {@link #PERFORMANCE}: latency, throughput, resource use. {@link #SECURITY}: authentication,
 * authorisation, confidentiality, integrity. {@link #RELIABILITY}: availability, fault tolerance,
 * recoverability. {@link #USABILITY}: learnability, accessibility, ergonomics.
 * {@link #MAINTAINABILITY}: modifiability, testability, analysability. {@link #OPERABILITY}:
 * deployment, monitoring, diagnosability in production. {@link #COMPLIANCE}: legal, regulatory or
 * contractual obligations. {@link #PORTABILITY}: adaptability across environments and platforms.
 */
public enum NfrCategory { PERFORMANCE, SECURITY, RELIABILITY, USABILITY, MAINTAINABILITY, OPERABILITY, COMPLIANCE, PORTABILITY }

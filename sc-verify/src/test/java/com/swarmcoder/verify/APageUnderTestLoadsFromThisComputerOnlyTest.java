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
package com.swarmcoder.verify;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A page under test is code a model wrote, shown in a browser on this computer. With outside
 * lookups off it may load from this computer and from nowhere else (section 90). This is the
 * rule the browser's request filter applies; no browser is started here.
 */
class APageUnderTestLoadsFromThisComputerOnlyTest {

    @Test
    void thisComputerAndAddressesThatAreNotRequestsAreLoaded() {
        assertThat(BrowserVerifier.mayLoad("http://127.0.0.1:41234/index.html")).isTrue();
        assertThat(BrowserVerifier.mayLoad("http://localhost:41234/app.js")).isTrue();
        assertThat(BrowserVerifier.mayLoad("ws://localhost:41234/rmi")).isTrue();
        assertThat(BrowserVerifier.mayLoad("data:image/png;base64,AAAA")).isTrue();
        assertThat(BrowserVerifier.mayLoad("blob:http://localhost:41234/1f2e")).isTrue();
        assertThat(BrowserVerifier.mayLoad("about:blank")).isTrue();
    }

    @Test
    void anyOtherHostIsNot() {
        assertThat(BrowserVerifier.mayLoad("https://cdn.example.com/library.js")).isFalse();
        assertThat(BrowserVerifier.mayLoad("http://collector.example.com/?book=title")).isFalse();
        assertThat(BrowserVerifier.mayLoad("wss://example.com/socket")).isFalse();
        assertThat(BrowserVerifier.mayLoad("HTTPS://EXAMPLE.COM/")).isFalse();
        assertThat(BrowserVerifier.mayLoad(null)).isFalse();
    }
}

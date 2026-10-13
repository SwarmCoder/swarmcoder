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

/**
 * An application that was started with the contract's {@code browser.serve} command and never
 * answered its ready probe, as facts (section 92): whether its command had ended, how, and what
 * it printed. Before this the only thing kept was "nothing answered within N seconds", and a
 * build stopped with no reason although the application had said why in its first lines.
 *
 * @param probeUrl       what was asked for an answer
 * @param inside         true when the application was started in a container
 * @param timeoutSeconds how long the contract lets an application take to answer
 * @param exited         how the serve command ended; null when it was still running when the
 *                       wait ended
 * @param waitedSeconds  how long was waited before giving up
 * @param output         what the command printed, both ends of it
 * @param outputRef      where the kept output was stored ({@link BlobSink}); null when it was
 *                       not stored
 */
public record StartFailure(String probeUrl, boolean inside, int timeoutSeconds,
                           ExecTarget.Exited exited, long waitedSeconds, ServeOutput output,
                           String outputRef) {

    public StartFailure {
        output = output == null ? ServeOutput.NONE : output;
    }

    /** One sentence, without a full stop: ended or still running, and the numbers. */
    public String sentence() {
        String where = probeUrl + (inside ? " inside the container" : "");
        if (exited != null) {
            return "the application exited "
                + (exited.exitCode() == null ? "(no exit code was recorded)"
                    : "with code " + exited.exitCode())
                + " after " + exited.afterSeconds() + " seconds, before anything answered "
                + where + ". Nothing was waited for after that";
        }
        return "nothing answered " + where + " within " + timeoutSeconds
            + " seconds, and the serve command was still running when the wait ended";
    }

    /** The same fact in a few words, for a place that has room for no more. */
    public String shortFact() {
        if (exited != null) {
            return "it exited " + (exited.exitCode() == null ? "with no exit code recorded"
                : "with code " + exited.exitCode()) + " after " + exited.afterSeconds()
                + " seconds";
        }
        return "it was still running and had not answered after " + timeoutSeconds + " seconds";
    }

    /** Where the kept output can be read in full, or "" when it was not stored. */
    public String whereToRead() {
        return outputRef == null || outputRef.isBlank() ? ""
            : "The output it printed (" + output.totalBytes() + " bytes; the first "
                + ServeOutput.FIRST_BYTES / 1024 + " KB and the last "
                + ServeOutput.LAST_BYTES / 1024 + " KB are kept) can be read with blob_text ref="
                + outputRef;
    }
}

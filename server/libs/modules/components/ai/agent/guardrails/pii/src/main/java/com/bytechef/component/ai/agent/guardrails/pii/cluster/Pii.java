/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.component.ai.agent.guardrails.pii.cluster;

import static com.bytechef.component.ai.agent.guardrails.constant.GuardrailsConstants.ENTITIES;
import static com.bytechef.component.ai.agent.guardrails.constant.GuardrailsConstants.TYPE;
import static com.bytechef.component.ai.agent.guardrails.constant.GuardrailsConstants.TYPE_ALL;
import static com.bytechef.component.ai.agent.guardrails.constant.GuardrailsConstants.TYPE_SELECTED;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.string;

import com.bytechef.component.ai.agent.guardrails.util.GuardrailProperties;
import com.bytechef.component.ai.agent.guardrails.util.PiiDetector;
import com.bytechef.component.ai.agent.guardrails.util.PiiDetector.PiiMatch;
import com.bytechef.component.ai.agent.guardrails.util.PiiDetector.PiiPattern;
import com.bytechef.component.definition.ClusterElementDefinition;
import com.bytechef.component.definition.ComponentDsl;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.Property;
import com.bytechef.platform.component.definition.ai.agent.guardrails.GuardrailCheckFunction;
import com.bytechef.platform.component.definition.ai.agent.guardrails.GuardrailContext;
import com.bytechef.platform.component.definition.ai.agent.guardrails.GuardrailSanitizerFunction;
import com.bytechef.platform.component.definition.ai.agent.guardrails.MaskResult;
import com.bytechef.platform.component.definition.ai.agent.guardrails.PreflightCheckFunction;
import com.bytechef.platform.component.definition.ai.agent.guardrails.PreflightSanitizerFunction;
import com.bytechef.platform.component.definition.ai.agent.guardrails.Violation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Rule-based personally-identifiable-information detection: emails, phone numbers, credit cards, IPs, IBANs, SSNs and
 * other locale-specific identifiers. Entity types are configurable and can be extended with operator-supplied regexes.
 * Emits detected spans as preflight-mask entities so both the check advisor and the sanitize advisor can redact them
 * before the LLM stage.
 *
 * @author Ivica Cardic
 */
public final class Pii {

    public static ClusterElementDefinition<GuardrailCheckFunction> ofCheck() {
        return ComponentDsl.<GuardrailCheckFunction>clusterElement("piiCheck")
            .title("PII")
            .description("Flags the input when personally identifiable information is detected.")
            .type(GuardrailCheckFunction.CHECK_FOR_VIOLATIONS)
            .properties(sharedProperties())
            .object(() -> new PreflightCheckFunction() {

                // Reference-equality cache of the compiled config per GuardrailContext. The advisor passes the same
                // context instance to apply() and mask() within one pass, so the second call hits the cache instead
                // of recompiling user regexes. Under concurrent requests a later call may evict an earlier entry;
                // correctness is preserved (each entry is keyed on its own context) and at worst the cache
                // degrades to the uncached behaviour.
                private final AtomicReference<CachedConfig> configCache = new AtomicReference<>();

                @Override
                public Optional<Violation> apply(String text, GuardrailContext context) {
                    return applyCheck(text, resolveConfig(context, configCache));
                }

                @Override
                public MaskResult mask(String text, GuardrailContext context) {
                    return MaskResult.entities(collectMaskEntities(text, resolveConfig(context, configCache)));
                }
            });
    }

    public static ClusterElementDefinition<GuardrailSanitizerFunction> ofSanitize() {
        return ComponentDsl.<GuardrailSanitizerFunction>clusterElement("piiSanitize")
            .title("PII")
            .description("Replaces detected personally identifiable information with placeholders.")
            .type(GuardrailSanitizerFunction.SANITIZE_TEXT)
            .properties(sharedProperties())
            .object(() -> new PreflightSanitizerFunction() {

                private final AtomicReference<CachedConfig> configCache = new AtomicReference<>();

                @Override
                public String apply(String text, GuardrailContext context) {
                    // Retained so the GuardrailSanitizerFunction contract is satisfied; the advisor never calls this
                    // for PreflightMasking sanitizers because mask() produces MaskResult.Entities (or Unchanged when
                    // nothing was detected) and Unchanged does not fall back to apply(). Kept wired to the same
                    // masking routine so direct callers (tests, ad-hoc use) get the expected redacted output.
                    return maskText(text, resolveConfig(context, configCache));
                }

                @Override
                public MaskResult mask(String text, GuardrailContext context) {
                    return MaskResult.entities(collectMaskEntities(text, resolveConfig(context, configCache)));
                }
            });
    }

    private Pii() {
    }

    private static Property[] sharedProperties() {
        return new Property[] {
            string(TYPE)
                .label("Type")
                .description("Scan for all available PII types or a user-selected subset. Add the standalone "
                    + "Custom Regex action to the agent if you also need to detect or sanitize "
                    + "project-specific patterns alongside PII.")
                .options(
                    option("All", TYPE_ALL),
                    option("Selected", TYPE_SELECTED))
                .defaultValue(TYPE_ALL)
                .required(true),
            array(ENTITIES)
                .label("Entities")
                .description("Which PII types to scan for.")
                .items(string())
                .options(PiiDetector.getPiiDetectionOptions())
                .displayCondition(TYPE + " == '" + TYPE_SELECTED + "'")
                .required(false),
            GuardrailProperties.failMode()
        };
    }

    private static List<PiiPattern> resolvePatterns(Parameters params) {
        String type = params.getString(TYPE, TYPE_ALL);

        if (TYPE_SELECTED.equals(type)) {
            return PiiDetector.filterByTypes(params.getList(ENTITIES, String.class));
        }

        return PiiDetector.DEFAULT_PII_PATTERNS;
    }

    private static CachedConfig resolveConfig(GuardrailContext context, AtomicReference<CachedConfig> cache) {
        CachedConfig cached = cache.get();

        if (cached != null && cached.context == context) {
            return cached;
        }

        Parameters inputParameters = context.inputParameters();
        CachedConfig fresh = new CachedConfig(context, resolvePatterns(inputParameters));

        cache.set(fresh);

        return fresh;
    }

    private static Optional<Violation> applyCheck(String text, CachedConfig config) {
        List<PiiMatch> matches = PiiDetector.detect(text, config.patterns(), List.of());

        if (matches.isEmpty()) {
            return Optional.empty();
        }

        List<String> values = matches.stream()
            .map(PiiMatch::value)
            .toList();

        List<String> entityTypes = matches.stream()
            .map(PiiMatch::type)
            .distinct()
            .toList();

        return Optional.of(Violation.ofMatches("piiCheck", values, Map.of("entityTypes", entityTypes)));
    }

    private static String maskText(String text, CachedConfig config) {
        List<PiiMatch> matches = PiiDetector.detect(text, config.patterns(), List.of());

        return PiiDetector.mask(text, matches);
    }

    /**
     * Collect PII matches keyed by type for the advisor's global length-sorted mask pass. The advisor merges these
     * across every preflight check and applies masks longest-first so overlapping matches (email vs. contained domain)
     * don't leave partial fragments.
     */
    private static Map<String, List<String>> collectMaskEntities(String text, CachedConfig config) {
        List<PiiMatch> matches = PiiDetector.detect(text, config.patterns(), List.of());

        if (matches.isEmpty()) {
            return Map.of();
        }

        Map<String, LinkedHashSet<String>> grouped = new LinkedHashMap<>();

        for (PiiMatch match : matches) {
            grouped.computeIfAbsent(match.type(), key -> new LinkedHashSet<>())
                .add(match.value());
        }

        Map<String, List<String>> result = new LinkedHashMap<>();

        grouped.forEach((type, values) -> result.put(type, new ArrayList<>(values)));

        return result;
    }

    /**
     * Resolved per-request configuration cached against the {@link GuardrailContext} reference so the patterns are
     * computed once per advisor pass instead of on every method invocation.
     */
    private record CachedConfig(GuardrailContext context, List<PiiPattern> patterns) {
    }
}

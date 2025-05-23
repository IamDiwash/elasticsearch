/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.action.support;

import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.TransportVersions;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.logging.DeprecationCategory;
import org.elasticsearch.common.logging.DeprecationLogger;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.ToXContent;
import org.elasticsearch.xcontent.ToXContentFragment;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParser.Token;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.elasticsearch.common.xcontent.support.XContentMapValues.nodeBooleanValue;
import static org.elasticsearch.common.xcontent.support.XContentMapValues.nodeStringArrayValue;

/**
 * Contains all the multi-target syntax options. These options are split into groups depending on what aspect of the syntax they
 * influence.
 *
 * @param concreteTargetOptions, applies only to concrete targets and defines how the response will handle when a concrete
 *                               target does not exist.
 * @param wildcardOptions, applies only to wildcard expressions and defines how the wildcards will be expanded and if it will
 *                        be acceptable to have expressions that results to no indices.
 * @param gatekeeperOptions, applies to all the resolved indices and defines if throttled will be included and if certain type of
 *                        aliases or indices are allowed, or they will throw an error. It acts as a gatekeeper when an action
 *                        does not support certain options.
 */
public record IndicesOptions(
    ConcreteTargetOptions concreteTargetOptions,
    WildcardOptions wildcardOptions,
    GatekeeperOptions gatekeeperOptions
) implements ToXContentFragment {

    public static IndicesOptions.Builder builder() {
        return new Builder();
    }

    public static IndicesOptions.Builder builder(IndicesOptions indicesOptions) {
        return new Builder(indicesOptions);
    }

    /**
     * Controls the way the target indices will be handled.
     * @param allowUnavailableTargets, if false when any of the concrete targets requested does not exist, throw an error
     */
    public record ConcreteTargetOptions(boolean allowUnavailableTargets) implements ToXContentFragment {
        public static final String IGNORE_UNAVAILABLE = "ignore_unavailable";
        public static final ConcreteTargetOptions ALLOW_UNAVAILABLE_TARGETS = new ConcreteTargetOptions(true);
        public static final ConcreteTargetOptions ERROR_WHEN_UNAVAILABLE_TARGETS = new ConcreteTargetOptions(false);

        public static ConcreteTargetOptions fromParameter(Object ignoreUnavailableString, ConcreteTargetOptions defaultOption) {
            if (ignoreUnavailableString == null && defaultOption != null) {
                return defaultOption;
            }
            return nodeBooleanValue(ignoreUnavailableString, IGNORE_UNAVAILABLE)
                ? ALLOW_UNAVAILABLE_TARGETS
                : ERROR_WHEN_UNAVAILABLE_TARGETS;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            return builder.field(IGNORE_UNAVAILABLE, allowUnavailableTargets);
        }
    }

    /**
     * Controls the way the wildcard expressions will be resolved.
     * @param matchOpen, open indices will be matched
     * @param matchClosed, closed indices will be matched
     * @param includeHidden, hidden indices will be included in the result. This is a post filter, it requires matchOpen or matchClosed
     *                      to have an effect.
     * @param resolveAliases, aliases will be included in the result, if false we treat them like they do not exist
     * @param allowEmptyExpressions, when an expression does not result in any indices, if false it throws an error if true it treats it as
     *                               an empty result
     */
    public record WildcardOptions(
        boolean matchOpen,
        boolean matchClosed,
        boolean includeHidden,
        boolean resolveAliases,
        boolean allowEmptyExpressions
    ) implements ToXContentFragment {

        public static final String EXPAND_WILDCARDS = "expand_wildcards";
        public static final String ALLOW_NO_INDICES = "allow_no_indices";

        public static final WildcardOptions DEFAULT = new WildcardOptions(true, false, false, true, true);

        public static WildcardOptions parseParameters(Object expandWildcards, Object allowNoIndices, WildcardOptions defaultOptions) {
            if (expandWildcards == null && allowNoIndices == null) {
                return defaultOptions;
            }
            WildcardOptions.Builder builder = defaultOptions == null ? new Builder() : new Builder(defaultOptions);
            if (expandWildcards != null) {
                builder.matchNone();
                builder.expandStates(nodeStringArrayValue(expandWildcards));
            }

            if (allowNoIndices != null) {
                builder.allowEmptyExpressions(nodeBooleanValue(allowNoIndices, ALLOW_NO_INDICES));
            }
            return builder.build();
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            return toXContent(builder, false);
        }

        /**
         * This converter to XContent only includes the fields a user can interact, internal options like the resolveAliases
         * are not added.
         * @param wildcardStatesAsUserInput, some parts of the code expect the serialization of the expand_wildcards field
         *                                   to be a comma separated string that matches the allowed user input, this includes
         *                                   all the states along with the values 'all' and 'none'.
         */
        public XContentBuilder toXContent(XContentBuilder builder, boolean wildcardStatesAsUserInput) throws IOException {
            EnumSet<WildcardStates> legacyStates = EnumSet.noneOf(WildcardStates.class);
            if (matchOpen()) {
                legacyStates.add(WildcardStates.OPEN);
            }
            if (matchClosed()) {
                legacyStates.add(WildcardStates.CLOSED);
            }
            if (includeHidden()) {
                legacyStates.add(WildcardStates.HIDDEN);
            }
            if (wildcardStatesAsUserInput) {
                if (legacyStates.isEmpty()) {
                    builder.field(EXPAND_WILDCARDS, "none");
                } else if (legacyStates.equals(EnumSet.allOf(WildcardStates.class))) {
                    builder.field(EXPAND_WILDCARDS, "all");
                } else {
                    builder.field(
                        EXPAND_WILDCARDS,
                        legacyStates.stream().map(WildcardStates::displayName).collect(Collectors.joining(","))
                    );
                }
            } else {
                builder.startArray(EXPAND_WILDCARDS);
                for (WildcardStates state : legacyStates) {
                    builder.value(state.displayName());
                }
                builder.endArray();
            }
            builder.field(ALLOW_NO_INDICES, allowEmptyExpressions());
            return builder;
        }

        public static class Builder {
            private boolean matchOpen;
            private boolean matchClosed;
            private boolean includeHidden;
            private boolean resolveAliases;
            private boolean allowEmptyExpressions;

            Builder() {
                this(DEFAULT);
            }

            Builder(WildcardOptions options) {
                matchOpen = options.matchOpen;
                matchClosed = options.matchClosed;
                includeHidden = options.includeHidden;
                resolveAliases = options.resolveAliases;
                allowEmptyExpressions = options.allowEmptyExpressions;
            }

            /**
             * Open indices will be matched. Defaults to true.
             */
            public Builder matchOpen(boolean matchOpen) {
                this.matchOpen = matchOpen;
                return this;
            }

            /**
             * Closed indices will be matched. Default to false.
             */
            public Builder matchClosed(boolean matchClosed) {
                this.matchClosed = matchClosed;
                return this;
            }

            /**
             * Hidden indices will be included from the result. Defaults to false.
             */
            public Builder includeHidden(boolean includeHidden) {
                this.includeHidden = includeHidden;
                return this;
            }

            /**
             * Aliases will be included in the result. Defaults to true.
             */
            public Builder resolveAliases(boolean resolveAliases) {
                this.resolveAliases = resolveAliases;
                return this;
            }

            /**
             * If true, when any of the expressions does not match any indices, we consider the result of this expression
             * empty; if all the expressions are empty then we have a successful but empty response.
             * If false, we throw an error immediately, so even if other expressions would result into indices the response
             * will contain only the error. Defaults to true.
             */
            public Builder allowEmptyExpressions(boolean allowEmptyExpressions) {
                this.allowEmptyExpressions = allowEmptyExpressions;
                return this;
            }

            /**
             * Disables expanding wildcards.
             */
            public Builder matchNone() {
                matchOpen = false;
                matchClosed = false;
                includeHidden = false;
                return this;
            }

            /**
             * Maximises the resolution of indices, we will match open, closed and hidden targets.
             */
            public Builder all() {
                matchOpen = true;
                matchClosed = true;
                includeHidden = true;
                return this;
            }

            /**
             * Parses the list of wildcard states to expand as provided by the user.
             * Logs a warning when the option 'none' is used along with other options because the position in the list
             * changes the outcome.
             */
            public Builder expandStates(String[] expandStates) {
                // Calling none() simulates a user providing an empty set of states
                matchNone();
                for (String expandState : expandStates) {
                    switch (expandState) {
                        case "open" -> matchOpen(true);
                        case "closed" -> matchClosed(true);
                        case "hidden" -> includeHidden(true);
                        case "all" -> all();
                        case "none" -> {
                            matchNone();
                            if (expandStates.length > 1) {
                                DEPRECATION_LOGGER.warn(DeprecationCategory.API, EXPAND_WILDCARDS, WILDCARD_NONE_DEPRECATION_MESSAGE);
                            }
                        }
                        default -> throw new IllegalArgumentException("No valid expand wildcard value [" + expandState + "]");
                    }
                }
                return this;
            }

            public WildcardOptions build() {
                return new WildcardOptions(matchOpen, matchClosed, includeHidden, resolveAliases, allowEmptyExpressions);
            }
        }

        public static Builder builder() {
            return new Builder();
        }

        public static Builder builder(WildcardOptions wildcardOptions) {
            return new Builder(wildcardOptions);
        }
    }

    /**
     * The "gatekeeper" options apply on all indices that have been selected by the other Options. It contains two type of flags:
     * - The "allow*" flags, which purpose is to enable actions to define certain conditions that need to apply on the concrete indices
     * they accept. For example, single-index actions will set allowAliasToMultipleIndices to false, while search will not accept a
     * closed index etc. These options are not configurable by the end-user.
     * - The ignoreThrottled flag, which is a deprecated flag that will filter out frozen indices.
     * @param allowAliasToMultipleIndices, allow aliases to multiple indices, true by default.
     * @param allowClosedIndices, allow closed indices, true by default.
     * @param allowSelectors, allow selectors within index expressions, true by default.
     * @param includeFailureIndices, when true includes the failure indices when a data stream or a data stream alias is encountered and
     *                              selectors are not allowed.
     * @param ignoreThrottled, filters out throttled (aka frozen indices), defaults to true. This is deprecated and the only one
     *                         that only filters and never throws an error.
     */
    public record GatekeeperOptions(
        boolean allowAliasToMultipleIndices,
        boolean allowClosedIndices,
        boolean allowSelectors,
        boolean includeFailureIndices,
        @Deprecated boolean ignoreThrottled
    ) implements ToXContentFragment {

        public static final String IGNORE_THROTTLED = "ignore_throttled";
        public static final GatekeeperOptions DEFAULT = new GatekeeperOptions(true, true, true, false, false);

        public static GatekeeperOptions parseParameter(Object ignoreThrottled, GatekeeperOptions defaultOptions) {
            if (ignoreThrottled == null && defaultOptions != null) {
                return defaultOptions;
            }
            return (defaultOptions == null ? new Builder() : new Builder(defaultOptions)).ignoreThrottled(
                nodeBooleanValue(ignoreThrottled, IGNORE_THROTTLED)
            ).build();
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            return builder.field(IGNORE_THROTTLED, ignoreThrottled());
        }

        public static class Builder {
            private boolean allowAliasToMultipleIndices;
            private boolean allowClosedIndices;
            private boolean allowSelectors;
            private boolean includeFailureIndices;
            private boolean ignoreThrottled;

            public Builder() {
                this(DEFAULT);
            }

            Builder(GatekeeperOptions options) {
                allowAliasToMultipleIndices = options.allowAliasToMultipleIndices;
                allowClosedIndices = options.allowClosedIndices;
                allowSelectors = options.allowSelectors;
                includeFailureIndices = options.includeFailureIndices;
                ignoreThrottled = options.ignoreThrottled;
            }

            /**
             * Aliases that resolve to multiple indices are accepted when true, otherwise the resolution will throw an error.
             * Defaults to true.
             */
            public Builder allowAliasToMultipleIndices(boolean allowAliasToMultipleIndices) {
                this.allowAliasToMultipleIndices = allowAliasToMultipleIndices;
                return this;
            }

            /**
             * Closed indices are accepted when true, otherwise the resolution will throw an error.
             * Defaults to true.
             */
            public Builder allowClosedIndices(boolean allowClosedIndices) {
                this.allowClosedIndices = allowClosedIndices;
                return this;
            }

            /**
             * Selectors are allowed within index expressions when true, otherwise the resolution will treat their presence as a syntax
             * error when resolving index expressions.
             * Defaults to true.
             */
            public Builder allowSelectors(boolean allowSelectors) {
                this.allowSelectors = allowSelectors;
                return this;
            }

            /**
             * When the selectors are not allowed, this flag determines if we will include the failure store
             * indices in the resolution or not. Defaults to false.
             */
            public Builder includeFailureIndices(boolean includeFailureIndices) {
                this.includeFailureIndices = includeFailureIndices;
                return this;
            }

            /**
             * Throttled indices will not be included in the result. Defaults to false.
             */
            public Builder ignoreThrottled(boolean ignoreThrottled) {
                this.ignoreThrottled = ignoreThrottled;
                return this;
            }

            public GatekeeperOptions build() {
                return new GatekeeperOptions(
                    allowAliasToMultipleIndices,
                    allowClosedIndices,
                    allowSelectors,
                    includeFailureIndices,
                    ignoreThrottled
                );
            }
        }

        public static Builder builder() {
            return new Builder();
        }

        public static Builder builder(GatekeeperOptions gatekeeperOptions) {
            return new Builder(gatekeeperOptions);
        }
    }

    /**
     * This class is maintained for backwards compatibility and performance purposes. We use it for serialisation along with {@link Option}.
     */
    private enum WildcardStates {
        OPEN,
        CLOSED,
        HIDDEN;

        static WildcardOptions toWildcardOptions(EnumSet<WildcardStates> states, boolean allowNoIndices, boolean ignoreAlias) {
            return WildcardOptions.builder()
                .matchOpen(states.contains(OPEN))
                .matchClosed(states.contains(CLOSED))
                .includeHidden(states.contains(HIDDEN))
                .allowEmptyExpressions(allowNoIndices)
                .resolveAliases(ignoreAlias == false)
                .build();
        }

        String displayName() {
            return toString().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * This class is maintained for backwards compatibility and performance purposes. We use it for serialisation along with
     * {@link WildcardStates}.
     */
    private enum Option {
        ALLOW_UNAVAILABLE_CONCRETE_TARGETS,
        EXCLUDE_ALIASES,
        ALLOW_EMPTY_WILDCARD_EXPRESSIONS,
        ERROR_WHEN_ALIASES_TO_MULTIPLE_INDICES,
        ERROR_WHEN_CLOSED_INDICES,
        IGNORE_THROTTLED,

        ALLOW_FAILURE_INDICES,  // Added in 8.14, Removed in 8.18
        ALLOW_SELECTORS,        // Added in 8.18
        INCLUDE_FAILURE_INDICES // Added in 8.18
    }

    private static final DeprecationLogger DEPRECATION_LOGGER = DeprecationLogger.getLogger(IndicesOptions.class);
    private static final String IGNORE_THROTTLED_DEPRECATION_MESSAGE = "[ignore_throttled] parameter is deprecated "
        + "because frozen indices have been deprecated. Consider cold or frozen tiers in place of frozen indices.";

    private static final String WILDCARD_NONE_DEPRECATION_MESSAGE = "Combining the value 'none' with other options is deprecated "
        + "because it is order sensitive. Please revise the expression to work without the 'none' option or only use 'none'.";

    public static final IndicesOptions DEFAULT = new IndicesOptions(
        ConcreteTargetOptions.ERROR_WHEN_UNAVAILABLE_TARGETS,
        WildcardOptions.DEFAULT,
        GatekeeperOptions.DEFAULT
    );

    public static final IndicesOptions STRICT_EXPAND_OPEN = IndicesOptions.builder()
        .concreteTargetOptions(ConcreteTargetOptions.ERROR_WHEN_UNAVAILABLE_TARGETS)
        .wildcardOptions(
            WildcardOptions.builder()
                .matchOpen(true)
                .matchClosed(false)
                .includeHidden(false)
                .allowEmptyExpressions(true)
                .resolveAliases(true)
        )
        .gatekeeperOptions(
            GatekeeperOptions.builder()
                .allowAliasToMultipleIndices(true)
                .allowClosedIndices(true)
                .allowSelectors(true)
                .ignoreThrottled(false)
        )
        .build();
    public static final IndicesOptions STRICT_EXPAND_OPEN_FAILURE_NO_SELECTOR = IndicesOptions.builder()
        .concreteTargetOptions(ConcreteTargetOptions.ERROR_WHEN_UNAVAILABLE_TARGETS)
        .wildcardOptions(
            WildcardOptions.builder()
                .matchOpen(true)
                .matchClosed(false)
                .includeHidden(false)
                .allowEmptyExpressions(true)
                .resolveAliases(true)
        )
        .gatekeeperOptions(
            GatekeeperOptions.builder()
                .allowAliasToMultipleIndices(true)
                .allowClosedIndices(true)
                .allowSelectors(false)
                .includeFailureIndices(true)
                .ignoreThrottled(false)
        )
        .build();
    public static final IndicesOptions LENIENT_EXPAND_OPEN = IndicesOptions.builder()
        .concreteTargetOptions(ConcreteTargetOptions.ALLOW_UNAVAILABLE_TARGETS)
        .wildcardOptions(
            WildcardOptions.builder()
                .matchOpen(true)
                .matchClosed(false)
                .includeHidden(false)
                .allowEmptyExpressions(true)
                .resolveAliases(true)
        )
        .gatekeeperOptions(
            GatekeeperOptions.builder()
                .allowAliasToMultipleIndices(true)
                .allowClosedIndices(true)
                .allowSelectors(true)
                .ignoreThrottled(false)
        )
        .build();
    public static final IndicesOptions LENIENT_EXPAND_OPEN_NO_SELECTORS = IndicesOptions.builder()
        .concreteTargetOptions(ConcreteTargetOptions.ALLOW_UNAVAILABLE_TARGETS)
        .wildcardOptions(
            WildcardOptions.builder()
                .matchOpen(true)
                .matchClosed(false)
                .includeHidden(false)
                .allowEmptyExpressions(true)
                .resolveAliases(true)
        )
        .gatekeeperOptions(
            GatekeeperOptions.builder()
                .allowAliasToMultipleIndices(true)
                .allowClosedIndices(true)
                .allowSelectors(false)
                .ignoreThrottled(false)
        )
        .build();
    public static final IndicesOptions LENIENT_EXPAND_OPEN_HIDDEN = IndicesOptions.builder()
        .concreteTargetOptions(ConcreteTargetOptions.ALLOW_UNAVAILABLE_TARGETS)
        .wildcardOptions(
            WildcardOptions.builder()
                .matchOpen(true)
                .matchClosed(false)
                .includeHidden(true)
                .allowEmptyExpressions(true)
                .resolveAliases(true)
        )
        .gatekeeperOptions(
            GatekeeperOptions.builder()
                .allowAliasToMultipleIndices(true)
                .allowClosedIndices(true)
                .allowSelectors(true)
                .ignoreThrottled(false)
        )
        .build();
    public static final IndicesOptions LENIENT_EXPAND_OPEN_CLOSED = IndicesOptions.builder()
        .concreteTargetOptions(ConcreteTargetOptions.ALLOW_UNAVAILABLE_TARGETS)
        .wildcardOptions(
            WildcardOptions.builder()
                .matchOpen(true)
                .matchClosed(true)
                .includeHidden(false)
                .allowEmptyExpressions(true)
                .resolveAliases(true)
        )
        .gatekeeperOptions(
            GatekeeperOptions.builder()
                .allowAliasToMultipleIndices(true)
                .allowClosedIndices(true)
                .allowSelectors(true)
                .ignoreThrottled(false)
        )
        .build();
    public static final IndicesOptions LENIENT_EXPAND_OPEN_CLOSED_HIDDEN = IndicesOptions.builder()
        .concreteTargetOptions(ConcreteTargetOptions.ALLOW_UNAVAILABLE_TARGETS)
        .wildcardOptions(
            WildcardOptions.builder().matchOpen(true).matchClosed(true).includeHidden(true).allowEmptyExpressions(true).resolveAliases(true)
        )
        .gatekeeperOptions(
            GatekeeperOptions.builder()
                .allowAliasToMultipleIndices(true)
                .allowClosedIndices(true)
                .allowSelectors(true)
                .ignoreThrottled(false)
        )
        .build();
    public static final IndicesOptions LENIENT_EXPAND_OPEN_CLOSED_HIDDEN_NO_SELECTOR = IndicesOptions.builder()
        .concreteTargetOptions(ConcreteTargetOptions.ALLOW_UNAVAILABLE_TARGETS)
        .wildcardOptions(
            WildcardOptions.builder().matchOpen(true).matchClosed(true).includeHidden(true).allowEmptyExpressions(true).resolveAliases(true)
        )
        .gatekeeperOptions(
            GatekeeperOptions.builder()
                .allowAliasToMultipleIndices(true)
                .allowClosedIndices(true)
                .allowSelectors(false)
                .ignoreThrottled(false)
        )
        .build();
    public static final IndicesOptions STRICT_EXPAND_OPEN_CLOSED = IndicesOptions.builder()
        .concreteTargetOptions(ConcreteTargetOptions.ERROR_WHEN_UNAVAILABLE_TARGETS)
        .wildcardOptions(
            WildcardOptions.builder()
                .matchOpen(true)
                .matchClosed(true)
                .includeHidden(false)
                .allowEmptyExpressions(true)
                .resolveAliases(true)
        )
        .gatekeeperOptions(
            GatekeeperOptions.builder()
                .allowAliasToMultipleIndices(true)
                .allowClosedIndices(true)
                .allowSelectors(true)
                .ignoreThrottled(false)
        )
        .build();
    public static final IndicesOptions STRICT_EXPAND_OPEN_CLOSED_HIDDEN = IndicesOptions.builder()
        .concreteTargetOptions(ConcreteTargetOptions.ERROR_WHEN_UNAVAILABLE_TARGETS)
        .wildcardOptions(
            WildcardOptions.builder().matchOpen(true).matchClosed(true).includeHidden(true).allowEmptyExpressions(true).resolveAliases(true)
        )
        .gatekeeperOptions(
            GatekeeperOptions.builder()
                .allowAliasToMultipleIndices(true)
                .allowClosedIndices(true)
                .allowSelectors(true)
                .ignoreThrottled(false)
        )
        .build();
    public static final IndicesOptions STRICT_EXPAND_OPEN_CLOSED_HIDDEN_NO_SELECTORS = IndicesOptions.builder()
        .concreteTargetOptions(ConcreteTargetOptions.ERROR_WHEN_UNAVAILABLE_TARGETS)
        .wildcardOptions(
            WildcardOptions.builder().matchOpen(true).matchClosed(true).includeHidden(true).allowEmptyExpressions(true).resolveAliases(true)
        )
        .gatekeeperOptions(
            GatekeeperOptions.builder()
                .allowAliasToMultipleIndices(true)
                .allowClosedIndices(true)
                .allowSelectors(false)
                .ignoreThrottled(false)
        )
        .build();
    public static final IndicesOptions STRICT_EXPAND_OPEN_CLOSED_HIDDEN_FAILURE_NO_SELECTORS = IndicesOptions.builder()
        .concreteTargetOptions(ConcreteTargetOptions.ERROR_WHEN_UNAVAILABLE_TARGETS)
        .wildcardOptions(
            WildcardOptions.builder().matchOpen(true).matchClosed(true).includeHidden(true).allowEmptyExpressions(true).resolveAliases(true)
        )
        .gatekeeperOptions(
            GatekeeperOptions.builder()
                .allowAliasToMultipleIndices(true)
                .allowClosedIndices(true)
                .allowSelectors(false)
                .includeFailureIndices(true)
                .ignoreThrottled(false)
        )
        .build();
    public static final IndicesOptions STRICT_EXPAND_OPEN_FORBID_CLOSED = IndicesOptions.builder()
        .concreteTargetOptions(ConcreteTargetOptions.ERROR_WHEN_UNAVAILABLE_TARGETS)
        .wildcardOptions(
            WildcardOptions.builder()
                .matchOpen(true)
                .matchClosed(false)
                .includeHidden(false)
                .allowEmptyExpressions(true)
                .resolveAliases(true)
        )
        .gatekeeperOptions(
            GatekeeperOptions.builder()
                .allowClosedIndices(false)
                .allowAliasToMultipleIndices(true)
                .allowSelectors(true)
                .ignoreThrottled(false)
        )
        .build();
    public static final IndicesOptions STRICT_EXPAND_OPEN_HIDDEN_FORBID_CLOSED = IndicesOptions.builder()
        .concreteTargetOptions(ConcreteTargetOptions.ERROR_WHEN_UNAVAILABLE_TARGETS)
        .wildcardOptions(
            WildcardOptions.builder()
                .matchOpen(true)
                .matchClosed(false)
                .includeHidden(true)
                .allowEmptyExpressions(true)
                .resolveAliases(true)
        )
        .gatekeeperOptions(
            GatekeeperOptions.builder()
                .allowClosedIndices(false)
                .allowAliasToMultipleIndices(true)
                .allowSelectors(true)
                .ignoreThrottled(false)
        )
        .build();
    public static final IndicesOptions STRICT_EXPAND_OPEN_FORBID_CLOSED_IGNORE_THROTTLED = IndicesOptions.builder()
        .concreteTargetOptions(ConcreteTargetOptions.ERROR_WHEN_UNAVAILABLE_TARGETS)
        .wildcardOptions(
            WildcardOptions.builder()
                .matchOpen(true)
                .matchClosed(false)
                .includeHidden(false)
                .allowEmptyExpressions(true)
                .resolveAliases(true)
        )
        .gatekeeperOptions(
            GatekeeperOptions.builder()
                .ignoreThrottled(true)
                .allowClosedIndices(false)
                .allowSelectors(true)
                .allowAliasToMultipleIndices(true)
        )
        .build();
    public static final IndicesOptions STRICT_SINGLE_INDEX_NO_EXPAND_FORBID_CLOSED = IndicesOptions.builder()
        .concreteTargetOptions(ConcreteTargetOptions.ERROR_WHEN_UNAVAILABLE_TARGETS)
        .wildcardOptions(
            WildcardOptions.builder()
                .matchOpen(false)
                .matchClosed(false)
                .includeHidden(false)
                .allowEmptyExpressions(true)
                .resolveAliases(true)
        )
        .gatekeeperOptions(
            GatekeeperOptions.builder()
                .allowAliasToMultipleIndices(false)
                .allowClosedIndices(false)
                .allowSelectors(false)
                .ignoreThrottled(false)
        )
        .build();
    public static final IndicesOptions STRICT_SINGLE_INDEX_NO_EXPAND_FORBID_CLOSED_ALLOW_SELECTORS = IndicesOptions.builder()
        .concreteTargetOptions(ConcreteTargetOptions.ERROR_WHEN_UNAVAILABLE_TARGETS)
        .wildcardOptions(
            WildcardOptions.builder()
                .matchOpen(false)
                .matchClosed(false)
                .includeHidden(false)
                .allowEmptyExpressions(true)
                .resolveAliases(true)
        )
        .gatekeeperOptions(
            GatekeeperOptions.builder()
                .allowAliasToMultipleIndices(false)
                .allowClosedIndices(false)
                .allowSelectors(true)
                .ignoreThrottled(false)
        )
        .build();
    public static final IndicesOptions STRICT_NO_EXPAND_FORBID_CLOSED = IndicesOptions.builder()
        .concreteTargetOptions(ConcreteTargetOptions.ERROR_WHEN_UNAVAILABLE_TARGETS)
        .wildcardOptions(
            WildcardOptions.builder()
                .matchOpen(false)
                .matchClosed(false)
                .includeHidden(false)
                .allowEmptyExpressions(true)
                .resolveAliases(true)
        )
        .gatekeeperOptions(
            GatekeeperOptions.builder()
                .allowClosedIndices(false)
                .allowAliasToMultipleIndices(true)
                .allowSelectors(true)
                .ignoreThrottled(false)
        )
        .build();

    /**
     * @return Whether specified concrete indices should be ignored when unavailable (missing or closed)
     */
    public boolean ignoreUnavailable() {
        return concreteTargetOptions.allowUnavailableTargets();
    }

    /**
     * @return Whether to ignore if a wildcard expression resolves to no concrete indices.
     * The `_all` string or empty list of indices count as wildcard expressions too.
     * Also when an alias points to a closed index this option decides if no concrete indices
     * are allowed.
     */
    public boolean allowNoIndices() {
        return wildcardOptions.allowEmptyExpressions();
    }

    /**
     * @return Whether wildcard expressions should get expanded to open indices
     */
    public boolean expandWildcardsOpen() {
        return wildcardOptions.matchOpen();
    }

    /**
     * @return Whether wildcard expressions should get expanded to closed indices
     */
    public boolean expandWildcardsClosed() {
        return wildcardOptions.matchClosed();
    }

    /**
     * @return whether wildcard expression should get expanded
     */
    public boolean expandWildcardExpressions() {
        // only check open/closed since if we do not expand to open or closed it doesn't make sense to
        // expand to hidden
        return expandWildcardsOpen() || expandWildcardsClosed();
    }

    /**
     * @return Whether wildcard expressions should get expanded to hidden indices
     */
    public boolean expandWildcardsHidden() {
        return wildcardOptions.includeHidden();
    }

    /**
     * @return Whether execution on closed indices is allowed.
     */
    public boolean forbidClosedIndices() {
        return gatekeeperOptions.allowClosedIndices() == false;
    }

    /**
     * @return Whether selectors (::) are allowed in the index expression.
     */
    public boolean allowSelectors() {
        return gatekeeperOptions.allowSelectors();
    }

    /**
     * @return true when selectors should be included in the resolution, false otherwise.
     */
    public boolean includeFailureIndices() {
        return gatekeeperOptions.includeFailureIndices();
    }

    /**
     * @return whether aliases pointing to multiple indices are allowed
     */
    public boolean allowAliasesToMultipleIndices() {
        return gatekeeperOptions().allowAliasToMultipleIndices();
    }

    /**
     * @return whether aliases should be ignored (when resolving a wildcard)
     */
    public boolean ignoreAliases() {
        return wildcardOptions.resolveAliases() == false;
    }

    /**
     * @return whether indices that are marked as throttled should be ignored
     */
    public boolean ignoreThrottled() {
        return gatekeeperOptions().ignoreThrottled();
    }

    public void writeIndicesOptions(StreamOutput out) throws IOException {
        EnumSet<Option> backwardsCompatibleOptions = EnumSet.noneOf(Option.class);
        if (allowNoIndices()) {
            backwardsCompatibleOptions.add(Option.ALLOW_EMPTY_WILDCARD_EXPRESSIONS);
        }
        if (ignoreAliases()) {
            backwardsCompatibleOptions.add(Option.EXCLUDE_ALIASES);
        }
        if (allowAliasesToMultipleIndices() == false) {
            backwardsCompatibleOptions.add(Option.ERROR_WHEN_ALIASES_TO_MULTIPLE_INDICES);
        }
        if (forbidClosedIndices()) {
            backwardsCompatibleOptions.add(Option.ERROR_WHEN_CLOSED_INDICES);
        }
        if (ignoreThrottled()) {
            backwardsCompatibleOptions.add(Option.IGNORE_THROTTLED);
        }
        if (ignoreUnavailable()) {
            backwardsCompatibleOptions.add(Option.ALLOW_UNAVAILABLE_CONCRETE_TARGETS);
        }
        // Until the feature flag is removed we access the field directly from the gatekeeper options.
        if (gatekeeperOptions().allowSelectors()) {
            if (out.getTransportVersion()
                .between(TransportVersions.V_8_14_0, TransportVersions.REPLACE_FAILURE_STORE_OPTIONS_WITH_SELECTOR_SYNTAX)) {
                backwardsCompatibleOptions.add(Option.ALLOW_FAILURE_INDICES);
            } else if (out.getTransportVersion().onOrAfter(TransportVersions.REPLACE_FAILURE_STORE_OPTIONS_WITH_SELECTOR_SYNTAX)) {
                backwardsCompatibleOptions.add(Option.ALLOW_SELECTORS);
            }
        }

        if (out.getTransportVersion().onOrAfter(TransportVersions.ADD_INCLUDE_FAILURE_INDICES_OPTION)
            && gatekeeperOptions.includeFailureIndices()) {
            backwardsCompatibleOptions.add(Option.INCLUDE_FAILURE_INDICES);
        }
        out.writeEnumSet(backwardsCompatibleOptions);

        EnumSet<WildcardStates> states = EnumSet.noneOf(WildcardStates.class);
        if (wildcardOptions.matchOpen()) {
            states.add(WildcardStates.OPEN);
        }
        if (wildcardOptions.matchClosed) {
            states.add(WildcardStates.CLOSED);
        }
        if (wildcardOptions.includeHidden()) {
            states.add(WildcardStates.HIDDEN);
        }
        out.writeEnumSet(states);
        if (out.getTransportVersion().between(TransportVersions.V_8_14_0, TransportVersions.V_8_16_0)) {
            out.writeBoolean(true);
            out.writeBoolean(false);
        }
        if (out.getTransportVersion()
            .between(TransportVersions.V_8_16_0, TransportVersions.REPLACE_FAILURE_STORE_OPTIONS_WITH_SELECTOR_SYNTAX)) {
            if (out.getTransportVersion().before(TransportVersions.V_8_17_0)) {
                out.writeVInt(1); // Enum set sized 1
                out.writeVInt(0); // ordinal 0 (::data selector)
            } else {
                out.writeByte((byte) 0); // ordinal 0 (::data selector)
            }
        }
    }

    public static IndicesOptions readIndicesOptions(StreamInput in) throws IOException {
        EnumSet<Option> options = in.readEnumSet(Option.class);
        WildcardOptions wildcardOptions = WildcardStates.toWildcardOptions(
            in.readEnumSet(WildcardStates.class),
            options.contains(Option.ALLOW_EMPTY_WILDCARD_EXPRESSIONS),
            options.contains(Option.EXCLUDE_ALIASES)
        );
        boolean allowSelectors = true;
        if (in.getTransportVersion()
            .between(TransportVersions.V_8_14_0, TransportVersions.REPLACE_FAILURE_STORE_OPTIONS_WITH_SELECTOR_SYNTAX)) {
            // We've effectively replaced the allow failure indices setting with allow selectors. If it is configured on an older version
            // then use its value for allow selectors.
            allowSelectors = options.contains(Option.ALLOW_FAILURE_INDICES);
        } else if (in.getTransportVersion().onOrAfter(TransportVersions.REPLACE_FAILURE_STORE_OPTIONS_WITH_SELECTOR_SYNTAX)) {
            allowSelectors = options.contains(Option.ALLOW_SELECTORS);
        }
        boolean includeFailureIndices = false;
        if (in.getTransportVersion().onOrAfter(TransportVersions.ADD_INCLUDE_FAILURE_INDICES_OPTION)) {
            includeFailureIndices = options.contains(Option.INCLUDE_FAILURE_INDICES);
        }
        GatekeeperOptions gatekeeperOptions = GatekeeperOptions.builder()
            .allowClosedIndices(options.contains(Option.ERROR_WHEN_CLOSED_INDICES) == false)
            .allowAliasToMultipleIndices(options.contains(Option.ERROR_WHEN_ALIASES_TO_MULTIPLE_INDICES) == false)
            .allowSelectors(allowSelectors)
            .includeFailureIndices(includeFailureIndices)
            .ignoreThrottled(options.contains(Option.IGNORE_THROTTLED))
            .build();
        if (in.getTransportVersion().between(TransportVersions.V_8_14_0, TransportVersions.V_8_16_0)) {
            // Reading from an older node, which will be sending two booleans that we must read out and ignore.
            in.readBoolean();
            in.readBoolean();
        }
        if (in.getTransportVersion()
            .between(TransportVersions.V_8_16_0, TransportVersions.REPLACE_FAILURE_STORE_OPTIONS_WITH_SELECTOR_SYNTAX)) {
            // Reading from an older node, which will be sending either an enum set or a single byte that needs to be read out and ignored.
            if (in.getTransportVersion().before(TransportVersions.V_8_17_0)) {
                int size = in.readVInt();
                for (int i = 0; i < size; i++) {
                    in.readVInt();
                }
            } else {
                in.readByte();
            }
        }
        return new IndicesOptions(
            options.contains(Option.ALLOW_UNAVAILABLE_CONCRETE_TARGETS)
                ? ConcreteTargetOptions.ALLOW_UNAVAILABLE_TARGETS
                : ConcreteTargetOptions.ERROR_WHEN_UNAVAILABLE_TARGETS,
            wildcardOptions,
            gatekeeperOptions
        );
    }

    public static class Builder {
        private ConcreteTargetOptions concreteTargetOptions;
        private WildcardOptions wildcardOptions;
        private GatekeeperOptions gatekeeperOptions;

        Builder() {
            this(DEFAULT);
        }

        Builder(IndicesOptions indicesOptions) {
            concreteTargetOptions = indicesOptions.concreteTargetOptions;
            wildcardOptions = indicesOptions.wildcardOptions;
            gatekeeperOptions = indicesOptions.gatekeeperOptions;
        }

        public Builder concreteTargetOptions(ConcreteTargetOptions concreteTargetOptions) {
            this.concreteTargetOptions = concreteTargetOptions;
            return this;
        }

        public Builder wildcardOptions(WildcardOptions wildcardOptions) {
            this.wildcardOptions = wildcardOptions;
            return this;
        }

        public Builder wildcardOptions(WildcardOptions.Builder wildcardOptions) {
            this.wildcardOptions = wildcardOptions.build();
            return this;
        }

        public Builder gatekeeperOptions(GatekeeperOptions gatekeeperOptions) {
            this.gatekeeperOptions = gatekeeperOptions;
            return this;
        }

        public Builder gatekeeperOptions(GatekeeperOptions.Builder generalOptions) {
            this.gatekeeperOptions = generalOptions.build();
            return this;
        }

        public IndicesOptions build() {
            return new IndicesOptions(concreteTargetOptions, wildcardOptions, gatekeeperOptions);
        }
    }

    public static IndicesOptions fromOptions(
        boolean ignoreUnavailable,
        boolean allowNoIndices,
        boolean expandToOpenIndices,
        boolean expandToClosedIndices
    ) {
        return fromOptions(ignoreUnavailable, allowNoIndices, expandToOpenIndices, expandToClosedIndices, false);
    }

    public static IndicesOptions fromOptions(
        boolean ignoreUnavailable,
        boolean allowNoIndices,
        boolean expandToOpenIndices,
        boolean expandToClosedIndices,
        boolean expandToHiddenIndices
    ) {
        return fromOptions(
            ignoreUnavailable,
            allowNoIndices,
            expandToOpenIndices,
            expandToClosedIndices,
            expandToHiddenIndices,
            true,
            false,
            false,
            false
        );
    }

    public static IndicesOptions fromOptions(
        boolean ignoreUnavailable,
        boolean allowNoIndices,
        boolean expandToOpenIndices,
        boolean expandToClosedIndices,
        IndicesOptions defaultOptions
    ) {
        return fromOptions(
            ignoreUnavailable,
            allowNoIndices,
            expandToOpenIndices,
            expandToClosedIndices,
            defaultOptions.expandWildcardsHidden(),
            defaultOptions.allowAliasesToMultipleIndices(),
            defaultOptions.forbidClosedIndices(),
            defaultOptions.ignoreAliases(),
            defaultOptions.ignoreThrottled()
        );
    }

    public static IndicesOptions fromOptions(
        boolean ignoreUnavailable,
        boolean allowNoIndices,
        boolean expandToOpenIndices,
        boolean expandToClosedIndices,
        boolean allowAliasesToMultipleIndices,
        boolean forbidClosedIndices,
        boolean ignoreAliases,
        boolean ignoreThrottled
    ) {
        return fromOptions(
            ignoreUnavailable,
            allowNoIndices,
            expandToOpenIndices,
            expandToClosedIndices,
            false,
            allowAliasesToMultipleIndices,
            forbidClosedIndices,
            ignoreAliases,
            ignoreThrottled
        );
    }

    public static IndicesOptions fromOptions(
        boolean ignoreUnavailable,
        boolean allowNoIndices,
        boolean expandToOpenIndices,
        boolean expandToClosedIndices,
        boolean expandToHiddenIndices,
        boolean allowAliasesToMultipleIndices,
        boolean forbidClosedIndices,
        boolean ignoreAliases,
        boolean ignoreThrottled
    ) {
        final WildcardOptions wildcards = WildcardOptions.builder()
            .matchOpen(expandToOpenIndices)
            .matchClosed(expandToClosedIndices)
            .includeHidden(expandToHiddenIndices)
            .resolveAliases(ignoreAliases == false)
            .allowEmptyExpressions(allowNoIndices)
            .build();
        final GatekeeperOptions gatekeeperOptions = GatekeeperOptions.builder()
            .allowAliasToMultipleIndices(allowAliasesToMultipleIndices)
            .allowClosedIndices(forbidClosedIndices == false)
            .ignoreThrottled(ignoreThrottled)
            .build();
        return new IndicesOptions(
            ignoreUnavailable ? ConcreteTargetOptions.ALLOW_UNAVAILABLE_TARGETS : ConcreteTargetOptions.ERROR_WHEN_UNAVAILABLE_TARGETS,
            wildcards,
            gatekeeperOptions
        );
    }

    public static IndicesOptions fromRequest(RestRequest request, IndicesOptions defaultSettings) {
        if (request.hasParam(GatekeeperOptions.IGNORE_THROTTLED)) {
            DEPRECATION_LOGGER.warn(DeprecationCategory.API, "ignore_throttled_param", IGNORE_THROTTLED_DEPRECATION_MESSAGE);
        }

        return fromParameters(
            request.param(WildcardOptions.EXPAND_WILDCARDS),
            request.param(ConcreteTargetOptions.IGNORE_UNAVAILABLE),
            request.param(WildcardOptions.ALLOW_NO_INDICES),
            request.param(GatekeeperOptions.IGNORE_THROTTLED),
            defaultSettings
        );
    }

    public static IndicesOptions fromMap(Map<String, Object> map, IndicesOptions defaultSettings) {
        return fromParameters(
            map.containsKey(WildcardOptions.EXPAND_WILDCARDS) ? map.get(WildcardOptions.EXPAND_WILDCARDS) : map.get("expandWildcards"),
            map.containsKey(ConcreteTargetOptions.IGNORE_UNAVAILABLE)
                ? map.get(ConcreteTargetOptions.IGNORE_UNAVAILABLE)
                : map.get("ignoreUnavailable"),
            map.containsKey(WildcardOptions.ALLOW_NO_INDICES) ? map.get(WildcardOptions.ALLOW_NO_INDICES) : map.get("allowNoIndices"),
            map.containsKey(GatekeeperOptions.IGNORE_THROTTLED) ? map.get(GatekeeperOptions.IGNORE_THROTTLED) : map.get("ignoreThrottled"),
            defaultSettings
        );
    }

    /**
     * Returns true if the name represents a valid name for one of the indices option
     * false otherwise
     */
    public static boolean isIndicesOptions(String name) {
        return WildcardOptions.EXPAND_WILDCARDS.equals(name)
            || "expandWildcards".equals(name)
            || ConcreteTargetOptions.IGNORE_UNAVAILABLE.equals(name)
            || "ignoreUnavailable".equals(name)
            || GatekeeperOptions.IGNORE_THROTTLED.equals(name)
            || "ignoreThrottled".equals(name)
            || WildcardOptions.ALLOW_NO_INDICES.equals(name)
            || "allowNoIndices".equals(name);
    }

    public static IndicesOptions fromParameters(
        Object wildcardsString,
        Object ignoreUnavailableString,
        Object allowNoIndicesString,
        Object ignoreThrottled,
        IndicesOptions defaultSettings
    ) {
        return fromParameters(wildcardsString, ignoreUnavailableString, allowNoIndicesString, ignoreThrottled, null, defaultSettings);
    }

    public static IndicesOptions fromParameters(
        Object wildcardsString,
        Object ignoreUnavailableString,
        Object allowNoIndicesString,
        Object ignoreThrottled,
        Object failureStoreString,
        IndicesOptions defaultSettings
    ) {
        if (wildcardsString == null
            && ignoreUnavailableString == null
            && allowNoIndicesString == null
            && ignoreThrottled == null
            && failureStoreString == null) {
            return defaultSettings;
        }

        WildcardOptions wildcards = WildcardOptions.parseParameters(wildcardsString, allowNoIndicesString, defaultSettings.wildcardOptions);
        GatekeeperOptions gatekeeperOptions = GatekeeperOptions.parseParameter(ignoreThrottled, defaultSettings.gatekeeperOptions);

        // note that allowAliasesToMultipleIndices is not exposed, always true (only for internal use)
        return IndicesOptions.builder()
            .concreteTargetOptions(ConcreteTargetOptions.fromParameter(ignoreUnavailableString, defaultSettings.concreteTargetOptions))
            .wildcardOptions(wildcards)
            .gatekeeperOptions(gatekeeperOptions)
            .build();
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, ToXContent.Params params) throws IOException {
        concreteTargetOptions.toXContent(builder, params);
        wildcardOptions.toXContent(builder, params);
        gatekeeperOptions.toXContent(builder, params);
        return builder;
    }

    private static final ParseField EXPAND_WILDCARDS_FIELD = new ParseField(WildcardOptions.EXPAND_WILDCARDS);
    private static final ParseField IGNORE_UNAVAILABLE_FIELD = new ParseField(ConcreteTargetOptions.IGNORE_UNAVAILABLE);
    private static final ParseField IGNORE_THROTTLED_FIELD = new ParseField(GatekeeperOptions.IGNORE_THROTTLED).withAllDeprecated();
    private static final ParseField ALLOW_NO_INDICES_FIELD = new ParseField(WildcardOptions.ALLOW_NO_INDICES);

    public static IndicesOptions fromXContent(XContentParser parser) throws IOException {
        return fromXContent(parser, null);
    }

    public static IndicesOptions fromXContent(XContentParser parser, @Nullable IndicesOptions defaults) throws IOException {
        boolean parsedWildcardStates = false;
        WildcardOptions.Builder wildcards = defaults == null ? null : WildcardOptions.builder(defaults.wildcardOptions());
        GatekeeperOptions.Builder generalOptions = GatekeeperOptions.builder()
            .ignoreThrottled(defaults != null && defaults.gatekeeperOptions().ignoreThrottled());
        Boolean allowNoIndices = defaults == null ? null : defaults.allowNoIndices();
        Boolean ignoreUnavailable = defaults == null ? null : defaults.ignoreUnavailable();
        Token token = parser.currentToken() == Token.START_OBJECT ? parser.currentToken() : parser.nextToken();
        String currentFieldName = null;
        if (token != Token.START_OBJECT) {
            throw new ElasticsearchParseException("expected START_OBJECT as the token but was " + token);
        }
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (token == Token.START_ARRAY) {
                if (EXPAND_WILDCARDS_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    if (parsedWildcardStates == false) {
                        parsedWildcardStates = true;
                        wildcards = WildcardOptions.builder();
                        List<String> values = new ArrayList<>();
                        while ((token = parser.nextToken()) != Token.END_ARRAY) {
                            if (token.isValue()) {
                                values.add(parser.text());
                            } else {
                                throw new ElasticsearchParseException(
                                    "expected values within array for " + EXPAND_WILDCARDS_FIELD.getPreferredName()
                                );
                            }
                        }
                        wildcards.expandStates(values.toArray(new String[] {}));
                    } else {
                        throw new ElasticsearchParseException("already parsed " + WildcardOptions.EXPAND_WILDCARDS);
                    }
                } else {
                    throw new ElasticsearchParseException(
                        EXPAND_WILDCARDS_FIELD.getPreferredName() + " is the only field that is an array in IndicesOptions"
                    );
                }
            } else if (token.isValue()) {
                if (EXPAND_WILDCARDS_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    if (parsedWildcardStates == false) {
                        parsedWildcardStates = true;
                        wildcards = WildcardOptions.builder();
                        wildcards.expandStates(new String[] { parser.text() });
                    } else {
                        throw new ElasticsearchParseException("already parsed " + WildcardOptions.EXPAND_WILDCARDS);
                    }
                } else if (IGNORE_UNAVAILABLE_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    ignoreUnavailable = parser.booleanValue();
                } else if (ALLOW_NO_INDICES_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    allowNoIndices = parser.booleanValue();
                } else if (IGNORE_THROTTLED_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    generalOptions.ignoreThrottled(parser.booleanValue());
                } else {
                    throw new ElasticsearchParseException(
                        "could not read indices options. Unexpected index option [" + currentFieldName + "]"
                    );
                }
            } else {
                throw new ElasticsearchParseException("could not read indices options. Unexpected object field [" + currentFieldName + "]");
            }
        }

        if (wildcards == null) {
            throw new ElasticsearchParseException("indices options xcontent did not contain " + EXPAND_WILDCARDS_FIELD.getPreferredName());
        } else {
            if (allowNoIndices == null) {
                throw new ElasticsearchParseException(
                    "indices options xcontent did not contain " + ALLOW_NO_INDICES_FIELD.getPreferredName()
                );
            } else {
                wildcards.allowEmptyExpressions(allowNoIndices);
            }
        }
        if (ignoreUnavailable == null) {
            throw new ElasticsearchParseException(
                "indices options xcontent did not contain " + IGNORE_UNAVAILABLE_FIELD.getPreferredName()
            );
        }
        return IndicesOptions.builder()
            .concreteTargetOptions(new ConcreteTargetOptions(ignoreUnavailable))
            .wildcardOptions(wildcards)
            .gatekeeperOptions(generalOptions)
            .build();
    }

    /**
     * @return indices options that requires every specified index to exist, expands wildcards only to open indices and
     * allows that no indices are resolved from wildcard expressions (not returning an error).
     */
    public static IndicesOptions strictExpandOpen() {
        return STRICT_EXPAND_OPEN;
    }

    /**
     * @return indices options that requires every specified index to exist, expands wildcards only to open indices and
     * allows that no indices are resolved from wildcard expressions (not returning an error). It disallows selectors
     * in the expression (no :: separators).
     */
    public static IndicesOptions strictExpandOpenFailureNoSelectors() {
        return STRICT_EXPAND_OPEN_FAILURE_NO_SELECTOR;
    }

    /**
     * @return indices options that requires every specified index to exist, expands wildcards only to open indices,
     * allows that no indices are resolved from wildcard expressions (not returning an error) and forbids the
     * use of closed indices by throwing an error.
     */
    public static IndicesOptions strictExpandOpenAndForbidClosed() {
        return STRICT_EXPAND_OPEN_FORBID_CLOSED;
    }

    /**
     * @return indices options that requires every specified index to exist, expands wildcards only to open indices,
     * allows that no indices are resolved from wildcard expressions (not returning an error),
     * forbids the use of closed indices by throwing an error and ignores indices that are throttled.
     */
    public static IndicesOptions strictExpandOpenAndForbidClosedIgnoreThrottled() {
        return STRICT_EXPAND_OPEN_FORBID_CLOSED_IGNORE_THROTTLED;
    }

    /**
     * @return indices option that requires every specified index to exist, expands wildcards to both open and closed
     * indices and allows that no indices are resolved from wildcard expressions (not returning an error).
     */
    public static IndicesOptions strictExpand() {
        return STRICT_EXPAND_OPEN_CLOSED;
    }

    /**
     * @return indices option that requires every specified index to exist, expands wildcards to both open and closed indices, includes
     * hidden indices, and allows that no indices are resolved from wildcard expressions (not returning an error).
     */
    public static IndicesOptions strictExpandHidden() {
        return STRICT_EXPAND_OPEN_CLOSED_HIDDEN;
    }

    /**
     * @return indices option that requires every specified index to exist, expands wildcards to both open and closed indices, includes
     * hidden indices, allows that no indices are resolved from wildcard expressions (not returning an error), and disallows selectors
     * in the expression (no :: separators).
     */
    public static IndicesOptions strictExpandHiddenNoSelectors() {
        return STRICT_EXPAND_OPEN_CLOSED_HIDDEN_NO_SELECTORS;
    }

    /**
     * @return indices option that requires every specified index to exist, expands wildcards to both open and closed indices, includes
     * hidden indices, allows that no indices are resolved from wildcard expressions (not returning an error), and disallows selectors
     * in the expression (no :: separators) but includes the failure indices.
     */
    public static IndicesOptions strictExpandHiddenFailureNoSelectors() {
        return STRICT_EXPAND_OPEN_CLOSED_HIDDEN_FAILURE_NO_SELECTORS;
    }

    /**
     * @return indices option that requires each specified index or alias to exist, doesn't expand wildcards.
     */
    public static IndicesOptions strictNoExpandForbidClosed() {
        return STRICT_NO_EXPAND_FORBID_CLOSED;
    }

    /**
     * @return indices option that requires each specified index or alias to exist, doesn't expand wildcards and
     * throws error if any of the aliases resolves to multiple indices
     */
    public static IndicesOptions strictSingleIndexNoExpandForbidClosed() {
        return STRICT_SINGLE_INDEX_NO_EXPAND_FORBID_CLOSED;
    }

    /**
     * @return indices option that requires each specified index or alias to exist, doesn't expand wildcards and
     * throws error if any of the aliases resolves to multiple indices
     */
    public static IndicesOptions strictSingleIndexNoExpandForbidClosedAllowSelectors() {
        return STRICT_SINGLE_INDEX_NO_EXPAND_FORBID_CLOSED_ALLOW_SELECTORS;
    }

    /**
     * @return indices options that ignores unavailable indices, expands wildcards only to open indices and
     * allows that no indices are resolved from wildcard expressions (not returning an error).
     */
    public static IndicesOptions lenientExpandOpen() {
        return LENIENT_EXPAND_OPEN;
    }

    /**
     * @return indices options that ignores unavailable indices, expands wildcards only to open indices,
     * allows that no indices are resolved from wildcard expressions (not returning an error), and disallows
     * selectors in the expression (no :: separators).
     */
    public static IndicesOptions lenientExpandOpenNoSelectors() {
        return LENIENT_EXPAND_OPEN_NO_SELECTORS;
    }

    /**
     * @return indices options that ignores unavailable indices, expands wildcards to open and hidden indices, and
     * allows that no indices are resolved from wildcard expressions (not returning an error).
     */
    public static IndicesOptions lenientExpandOpenHidden() {
        return LENIENT_EXPAND_OPEN_HIDDEN;
    }

    /**
     * @return indices options that ignores unavailable indices,  expands wildcards to both open and closed
     * indices and allows that no indices are resolved from wildcard expressions (not returning an error).
     */
    public static IndicesOptions lenientExpand() {
        return LENIENT_EXPAND_OPEN_CLOSED;
    }

    /**
     * @return indices options that ignores unavailable indices,  expands wildcards to all open and closed
     * indices and allows that no indices are resolved from wildcard expressions (not returning an error).
     */
    public static IndicesOptions lenientExpandHidden() {
        return LENIENT_EXPAND_OPEN_CLOSED_HIDDEN;
    }

    @Override
    public String toString() {
        return "IndicesOptions["
            + "ignore_unavailable="
            + ignoreUnavailable()
            + ", allow_no_indices="
            + allowNoIndices()
            + ", expand_wildcards_open="
            + expandWildcardsOpen()
            + ", expand_wildcards_closed="
            + expandWildcardsClosed()
            + ", expand_wildcards_hidden="
            + expandWildcardsHidden()
            + ", allow_aliases_to_multiple_indices="
            + allowAliasesToMultipleIndices()
            + ", forbid_closed_indices="
            + forbidClosedIndices()
            + ", ignore_aliases="
            + ignoreAliases()
            + ", ignore_throttled="
            + ignoreThrottled()
            + ", allow_selectors="
            + allowSelectors()
            + ", include_failure_indices="
            + includeFailureIndices()
            + ']';
    }
}

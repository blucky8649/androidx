/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.wear.protolayout.expression.pipeline;

import static java.util.Collections.emptyMap;

import android.annotation.SuppressLint;
import android.icu.util.ULocale;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.RestrictTo.Scope;
import androidx.annotation.VisibleForTesting;
import androidx.collection.ArrayMap;
import androidx.wear.protolayout.expression.DynamicBuilders;
import androidx.wear.protolayout.expression.PlatformDataKey;
import androidx.wear.protolayout.expression.pipeline.BoolNodes.ComparisonFloatNode;
import androidx.wear.protolayout.expression.pipeline.BoolNodes.ComparisonInt32Node;
import androidx.wear.protolayout.expression.pipeline.BoolNodes.FixedBoolNode;
import androidx.wear.protolayout.expression.pipeline.BoolNodes.LogicalBoolOp;
import androidx.wear.protolayout.expression.pipeline.BoolNodes.NotBoolOp;
import androidx.wear.protolayout.expression.pipeline.BoolNodes.StateBoolNode;
import androidx.wear.protolayout.expression.pipeline.ColorNodes.AnimatableFixedColorNode;
import androidx.wear.protolayout.expression.pipeline.ColorNodes.DynamicAnimatedColorNode;
import androidx.wear.protolayout.expression.pipeline.ColorNodes.FixedColorNode;
import androidx.wear.protolayout.expression.pipeline.ColorNodes.StateColorSourceNode;
import androidx.wear.protolayout.expression.pipeline.DurationNodes.BetweenInstancesNode;
import androidx.wear.protolayout.expression.pipeline.DurationNodes.FixedDurationNode;
import androidx.wear.protolayout.expression.pipeline.FloatNodes.AnimatableFixedFloatNode;
import androidx.wear.protolayout.expression.pipeline.FloatNodes.ArithmeticFloatNode;
import androidx.wear.protolayout.expression.pipeline.FloatNodes.DynamicAnimatedFloatNode;
import androidx.wear.protolayout.expression.pipeline.FloatNodes.FixedFloatNode;
import androidx.wear.protolayout.expression.pipeline.FloatNodes.Int32ToFloatNode;
import androidx.wear.protolayout.expression.pipeline.FloatNodes.StateFloatSourceNode;
import androidx.wear.protolayout.expression.pipeline.InstantNodes.FixedInstantNode;
import androidx.wear.protolayout.expression.pipeline.InstantNodes.PlatformTimeSourceNode;
import androidx.wear.protolayout.expression.pipeline.Int32Nodes.AnimatableFixedInt32Node;
import androidx.wear.protolayout.expression.pipeline.Int32Nodes.ArithmeticInt32Node;
import androidx.wear.protolayout.expression.pipeline.Int32Nodes.DynamicAnimatedInt32Node;
import androidx.wear.protolayout.expression.pipeline.Int32Nodes.FixedInt32Node;
import androidx.wear.protolayout.expression.pipeline.Int32Nodes.FloatToInt32Node;
import androidx.wear.protolayout.expression.pipeline.Int32Nodes.GetDurationPartOpNode;
import androidx.wear.protolayout.expression.pipeline.Int32Nodes.LegacyPlatformInt32SourceNode;
import androidx.wear.protolayout.expression.pipeline.Int32Nodes.StateInt32SourceNode;
import androidx.wear.protolayout.expression.pipeline.StringNodes.FixedStringNode;
import androidx.wear.protolayout.expression.pipeline.StringNodes.FloatFormatNode;
import androidx.wear.protolayout.expression.pipeline.StringNodes.Int32FormatNode;
import androidx.wear.protolayout.expression.pipeline.StringNodes.StateStringNode;
import androidx.wear.protolayout.expression.pipeline.StringNodes.StringConcatOpNode;
import androidx.wear.protolayout.expression.proto.DynamicProto;
import androidx.wear.protolayout.expression.proto.DynamicProto.AnimatableDynamicColor;
import androidx.wear.protolayout.expression.proto.DynamicProto.AnimatableDynamicFloat;
import androidx.wear.protolayout.expression.proto.DynamicProto.AnimatableDynamicInt32;
import androidx.wear.protolayout.expression.proto.DynamicProto.ConditionalColorOp;
import androidx.wear.protolayout.expression.proto.DynamicProto.ConditionalDurationOp;
import androidx.wear.protolayout.expression.proto.DynamicProto.ConditionalFloatOp;
import androidx.wear.protolayout.expression.proto.DynamicProto.ConditionalInstantOp;
import androidx.wear.protolayout.expression.proto.DynamicProto.ConditionalInt32Op;
import androidx.wear.protolayout.expression.proto.DynamicProto.ConditionalStringOp;
import androidx.wear.protolayout.expression.proto.DynamicProto.DynamicBool;
import androidx.wear.protolayout.expression.proto.DynamicProto.DynamicColor;
import androidx.wear.protolayout.expression.proto.DynamicProto.DynamicDuration;
import androidx.wear.protolayout.expression.proto.DynamicProto.DynamicFloat;
import androidx.wear.protolayout.expression.proto.DynamicProto.DynamicInstant;
import androidx.wear.protolayout.expression.proto.DynamicProto.DynamicInt32;
import androidx.wear.protolayout.expression.proto.DynamicProto.DynamicString;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Evaluates protolayout dynamic types.
 *
 * <p>Given a dynamic ProtoLayout data source, this builds up a sequence of {@link DynamicDataNode}
 * instances, which can source the required data, and transform it into its final form.
 *
 * <p>Data source can include animations which will then emit value transitions.
 *
 * <p>In order to evaluate dynamic types, the caller needs to create a {@link
 * DynamicTypeBindingRequest}, bind it using {@link #bind(DynamicTypeBindingRequest)} method and
 * then call {@link BoundDynamicType#startEvaluation()} on the resulted {@link BoundDynamicType} to
 * start evaluation. Starting evaluation can be done for batches of dynamic types.
 *
 * <p>It's the callers responsibility to destroy those dynamic types after use, with {@link
 * BoundDynamicType#close()}.
 */
public class DynamicTypeEvaluator {
    private static final String TAG = "DynamicTypeEvaluator";
    private static final QuotaManager NO_OP_QUOTA_MANAGER =
            new FixedQuotaManagerImpl(Integer.MAX_VALUE, "dynamic nodes noop");

    @NonNull
    private static final QuotaManager DISABLED_ANIMATIONS_QUOTA_MANAGER =
            new QuotaManager() {
                @Override
                public boolean tryAcquireQuota(int quota) {
                    return false;
                }

                @Override
                public void releaseQuota(int quota) {
                    throw new IllegalStateException(
                            "releaseQuota method is called when no quota is acquired!");
                }
            };

    /** Exception thrown when the binding of a {@link DynamicTypeBindingRequest} fails. */
    public static class EvaluationException extends Exception {
        public EvaluationException(@NonNull String message) {
            super(message);
        }
    }

    @NonNull private static final StateStore EMPTY_STATE_STORE = new StateStore(emptyMap());

    @NonNull private final StateStore mStateStore;
    @NonNull private final PlatformDataStore mPlatformDataStore;
    @NonNull private final QuotaManager mAnimationQuotaManager;
    @NonNull private final QuotaManager mDynamicTypesQuotaManager;
    @NonNull private final EpochTimePlatformDataSource mTimeDataSource;

    /** Configuration for creating {@link DynamicTypeEvaluator}. */
    public static final class Config {
        @Nullable private final StateStore mStateStore;
        @Nullable private final QuotaManager mAnimationQuotaManager;
        @Nullable private final QuotaManager mDynamicTypesQuotaManager;
        @NonNull private final Map<PlatformDataKey<?>, PlatformDataProvider>
                mSourceKeyToDataProviders = new ArrayMap<>();
        @Nullable private final PlatformTimeUpdateNotifier mPlatformTimeUpdateNotifier;
        @Nullable private final Supplier<Instant> mClock;

        Config(
                @Nullable StateStore stateStore,
                @Nullable QuotaManager animationQuotaManager,
                @Nullable QuotaManager dynamicTypesQuotaManager,
                @NonNull Map<PlatformDataKey<?>, PlatformDataProvider>
                        sourceKeyToDataProviders,
                @Nullable PlatformTimeUpdateNotifier platformTimeUpdateNotifier,
                @Nullable Supplier<Instant> clock) {
            this.mStateStore = stateStore;
            this.mAnimationQuotaManager = animationQuotaManager;
            this.mDynamicTypesQuotaManager = dynamicTypesQuotaManager;
            this.mSourceKeyToDataProviders.putAll(sourceKeyToDataProviders);
            this.mPlatformTimeUpdateNotifier = platformTimeUpdateNotifier;
            this.mClock = clock;
        }

        /** Builds a {@link DynamicTypeEvaluator.Config}. */
        public static final class Builder {
            @Nullable private StateStore mStateStore = null;
            @Nullable private QuotaManager mAnimationQuotaManager = null;
            @Nullable private QuotaManager mDynamicTypesQuotaManager = null;
            @NonNull private final Map<PlatformDataKey<?>, PlatformDataProvider>
                    mSourceKeyToDataProviders = new ArrayMap<>();
            @Nullable private PlatformTimeUpdateNotifier mPlatformTimeUpdateNotifier = null;
            @Nullable private Supplier<Instant> mClock = null;

            /**
             * Sets the state store that will be used for dereferencing the state keys in the
             * dynamic types.
             *
             * <p>If not set, it's the equivalent of setting an empty state store (state bindings
             * will trigger {@link DynamicTypeValueReceiver#onInvalidated()}).
             */
            @NonNull
            public Builder setStateStore(@NonNull StateStore value) {
                mStateStore = value;
                return this;
            }

            /**
             * Sets the quota manager used for limiting the number of concurrently running
             * animations.
             *
             * <p>If not set, animations are disabled and non-infinite animations will have the end
             * value immediately.
             */
            @NonNull
            public Builder setAnimationQuotaManager(@NonNull QuotaManager value) {
                mAnimationQuotaManager = value;
                return this;
            }

            /**
             * Sets the quota manager used for limiting the total size of dynamic types in the
             * pipeline.
             *
             * <p>If not set, number of dynamic types will not be restricted.
             */
            @NonNull
            public Builder setDynamicTypesQuotaManager(@NonNull QuotaManager value) {
                mDynamicTypesQuotaManager = value;
                return this;
            }

            /**
             * Add a platform data provider and specify the keys it can provide dynamic data for.
             *
             * <p> The provider must support at least one key. If the provider supports multiple
             * keys, they should not be independent, as their values should always update together.
             * One data key must not have multiple providers, or an exception will be thrown.
             *
             * @throws IllegalArgumentException If a PlatformDataProvider supports an empty key
             * set or if a key has multiple data providers.
             */
            @SuppressLint("MissingGetterMatchingBuilder")
            @NonNull
            public Builder addPlatformDataProvider(
                    @NonNull PlatformDataProvider platformDataProvider,
                    @NonNull Set<PlatformDataKey<?>> supportedDataKeys
            ) {
                if (supportedDataKeys.isEmpty()) {
                    throw new IllegalArgumentException(
                            "The PlatformDataProvider must support at least one key");
                }
                for (PlatformDataKey<?> dataKey : supportedDataKeys) {
                    // Throws exception when one data key has multiple providers.
                    if (mSourceKeyToDataProviders.containsKey(dataKey)) {
                        throw new IllegalArgumentException(String.format(
                                "Multiple data providers for PlatformDataKey (%s)", dataKey));
                    }
                    mSourceKeyToDataProviders.put(dataKey, platformDataProvider);
                }

                return this;
            }

            /**
             * Sets the notifier used for updating the platform time data. If not set, by default
             * platform time will be updated at 1Hz using a {@code Handler} on the main thread.
             */
            @NonNull
            public Builder setPlatformTimeUpdateNotifier(
                    @NonNull PlatformTimeUpdateNotifier notifier) {
                this.mPlatformTimeUpdateNotifier = notifier;
                return this;
            }

            /**
             * Sets the clock ({@link Instant} supplier) used for providing time data to bindings.
             * If not set, on every reevaluation, platform time for dynamic values will be set to
             * {@link Instant#now()}.
             */
            @VisibleForTesting
            @NonNull
            public Builder setClock(@NonNull Supplier<Instant> clock) {
                this.mClock = clock;
                return this;
            }

            @NonNull
            public Config build() {
                return new Config(
                        mStateStore,
                        mAnimationQuotaManager,
                        mDynamicTypesQuotaManager,
                        mSourceKeyToDataProviders,
                        mPlatformTimeUpdateNotifier,
                        mClock);
            }
        }

        /**
         * Gets the state store that will be used for dereferencing the state keys in the dynamic
         * types, or {@code null} which is equivalent to an empty state store (state bindings will
         * trigger {@link DynamicTypeValueReceiver#onInvalidated()}).
         */
        @Nullable
        public StateStore getStateStore() {
            return mStateStore;
        }

        /**
         * Gets the quota manager used for limiting the number of concurrently running animations,
         * or {@code null} if animations are disabled, causing non-infinite animations to have to
         * the end value immediately.
         */
        @Nullable
        public QuotaManager getAnimationQuotaManager() {
            return mAnimationQuotaManager;
        }

        /**
         * Gets the quota manager used for limiting the total number of dynamic types in the
         * pipeline, or {@code null} if there are no restriction on the number of dynamic types. If
         * present, the quota manager is used to prevent unreasonably expensive expressions.
         */
        @Nullable
        public QuotaManager getDynamicTypesQuotaManager() {
            return mDynamicTypesQuotaManager;
        }

        /**
         * Returns any available mapping between source key and its data provider.
         */
        @NonNull
        public Map<PlatformDataKey<?>, PlatformDataProvider> getPlatformDataProviders() {
            return new ArrayMap<>(
                    (ArrayMap<PlatformDataKey<?>, PlatformDataProvider>) mSourceKeyToDataProviders);
        }

        /**
         * Returns the clock ({@link Instant} supplier) used for providing time data to bindings, or
         * {@code null} which means on every reevaluation, platform time for dynamic values will be
         * set to {@link Instant#now()}.
         */
        @VisibleForTesting
        @Nullable
        public Supplier<Instant> getClock() {
            return mClock;
        }

        /** Gets the notifier used for updating the platform time data. */
        @Nullable
        public PlatformTimeUpdateNotifier getPlatformTimeUpdateNotifier() {
            return mPlatformTimeUpdateNotifier;
        }
    }

    /** Constructs a {@link DynamicTypeEvaluator}. */
    public DynamicTypeEvaluator(@NonNull Config config) {
        this.mStateStore =
                config.getStateStore() != null ? config.getStateStore() : EMPTY_STATE_STORE;
        this.mAnimationQuotaManager =
                config.getAnimationQuotaManager() != null
                        ? config.getAnimationQuotaManager()
                        : DISABLED_ANIMATIONS_QUOTA_MANAGER;
        this.mDynamicTypesQuotaManager =
                config.getDynamicTypesQuotaManager() != null
                        ? config.getDynamicTypesQuotaManager()
                        : NO_OP_QUOTA_MANAGER;
        this.mPlatformDataStore = new PlatformDataStore(config.getPlatformDataProviders());
        PlatformTimeUpdateNotifier notifier =
                config.getPlatformTimeUpdateNotifier();
        if (notifier == null) {
            notifier = new PlatformTimeUpdateNotifierImpl();
            ((PlatformTimeUpdateNotifierImpl) notifier).setUpdatesEnabled(true);
        }
        Supplier<Instant> clock = config.getClock() != null ? config.getClock() : Instant::now;
        this.mTimeDataSource = new EpochTimePlatformDataSource(clock, notifier);
    }

    /**
     * Binds a {@link DynamicTypeBindingRequest}.
     *
     * <p>Evaluation of this request will start when {@link BoundDynamicType#startEvaluation()} is
     * called on the returned object.
     *
     * @throws EvaluationException when {@link QuotaManager} fails to allocate enough quota to bind
     *     the {@link DynamicTypeBindingRequest}.
     */
    @NonNull
    public BoundDynamicType bind(@NonNull DynamicTypeBindingRequest request)
            throws EvaluationException {
        BoundDynamicTypeImpl boundDynamicType = request.callBindOn(this);
        if (!mDynamicTypesQuotaManager.tryAcquireQuota(boundDynamicType.getDynamicNodeCount())) {
            throw new EvaluationException(
                    "Dynamic type expression limit reached. Try making the dynamic type expression"
                            + " shorter or reduce the number of dynamic type expressions.");
        }
        return boundDynamicType;
    }

    @NonNull
    BoundDynamicTypeImpl bindInternal(
            @NonNull DynamicBuilders.DynamicString stringSource,
            @NonNull ULocale locale,
            @NonNull Executor executor,
            @NonNull DynamicTypeValueReceiver<String> consumer) {
        return bindInternal(
                stringSource.toDynamicStringProto(),
                locale,
                new DynamicTypeValueReceiverOnExecutor<>(executor, consumer));
    }

    @NonNull
    @RestrictTo(Scope.LIBRARY_GROUP)
    BoundDynamicTypeImpl bindInternal(
            @NonNull DynamicString stringSource,
            @NonNull ULocale locale,
            @NonNull DynamicTypeValueReceiver<String> consumer) {
        List<DynamicDataNode<?>> resultBuilder = new ArrayList<>();
        bindRecursively(
                stringSource,
                new DynamicTypeValueReceiverOnExecutor<>(consumer),
                locale,
                resultBuilder);
        return new BoundDynamicTypeImpl(resultBuilder, mDynamicTypesQuotaManager);
    }

    @NonNull
    BoundDynamicTypeImpl bindInternal(
            @NonNull DynamicBuilders.DynamicInt32 int32Source,
            @NonNull Executor executor,
            @NonNull DynamicTypeValueReceiver<Integer> consumer) {
        return bindInternal(
                int32Source.toDynamicInt32Proto(),
                new DynamicTypeValueReceiverOnExecutor<>(executor, consumer));
    }

    @NonNull
    @RestrictTo(Scope.LIBRARY_GROUP)
    BoundDynamicTypeImpl bindInternal(
            @NonNull DynamicInt32 int32Source,
            @NonNull DynamicTypeValueReceiver<Integer> consumer) {
        List<DynamicDataNode<?>> resultBuilder = new ArrayList<>();
        bindRecursively(
                int32Source, new DynamicTypeValueReceiverOnExecutor<>(consumer), resultBuilder);
        return new BoundDynamicTypeImpl(resultBuilder, mDynamicTypesQuotaManager);
    }

    @NonNull
    BoundDynamicTypeImpl bindInternal(
            @NonNull DynamicBuilders.DynamicFloat floatSource,
            @NonNull Executor executor,
            @NonNull DynamicTypeValueReceiver<Float> consumer) {
        return bindInternal(
                floatSource.toDynamicFloatProto(),
                new DynamicTypeValueReceiverOnExecutor<>(executor, consumer));
    }

    @NonNull
    @RestrictTo(Scope.LIBRARY_GROUP)
    BoundDynamicTypeImpl bindInternal(
            @NonNull DynamicFloat floatSource, @NonNull DynamicTypeValueReceiver<Float> consumer) {
        List<DynamicDataNode<?>> resultBuilder = new ArrayList<>();
        bindRecursively(
                floatSource, new DynamicTypeValueReceiverOnExecutor<>(consumer), resultBuilder);
        return new BoundDynamicTypeImpl(resultBuilder, mDynamicTypesQuotaManager);
    }

    @NonNull
    BoundDynamicTypeImpl bindInternal(
            @NonNull DynamicBuilders.DynamicColor colorSource,
            @NonNull Executor executor,
            @NonNull DynamicTypeValueReceiver<Integer> consumer) {
        return bindInternal(
                colorSource.toDynamicColorProto(),
                new DynamicTypeValueReceiverOnExecutor<>(executor, consumer));
    }

    @NonNull
    @RestrictTo(Scope.LIBRARY_GROUP)
    BoundDynamicTypeImpl bindInternal(
            @NonNull DynamicColor colorSource,
            @NonNull DynamicTypeValueReceiver<Integer> consumer) {
        List<DynamicDataNode<?>> resultBuilder = new ArrayList<>();
        bindRecursively(
                colorSource, new DynamicTypeValueReceiverOnExecutor<>(consumer), resultBuilder);
        return new BoundDynamicTypeImpl(resultBuilder, mDynamicTypesQuotaManager);
    }

    @NonNull
    BoundDynamicTypeImpl bindInternal(
            @NonNull DynamicBuilders.DynamicDuration durationSource,
            @NonNull Executor executor,
            @NonNull DynamicTypeValueReceiver<Duration> consumer) {
        return bindInternal(
                durationSource.toDynamicDurationProto(),
                new DynamicTypeValueReceiverOnExecutor<>(executor, consumer));
    }

    @NonNull
    @RestrictTo(Scope.LIBRARY_GROUP)
    BoundDynamicTypeImpl bindInternal(
            @NonNull DynamicDuration durationSource,
            @NonNull DynamicTypeValueReceiver<Duration> consumer) {
        List<DynamicDataNode<?>> resultBuilder = new ArrayList<>();
        bindRecursively(
                durationSource, new DynamicTypeValueReceiverOnExecutor<>(consumer), resultBuilder);
        return new BoundDynamicTypeImpl(resultBuilder, mDynamicTypesQuotaManager);
    }

    @NonNull
    BoundDynamicTypeImpl bindInternal(
            @NonNull DynamicBuilders.DynamicInstant instantSource,
            @NonNull Executor executor,
            @NonNull DynamicTypeValueReceiver<Instant> consumer) {
        return bindInternal(
                instantSource.toDynamicInstantProto(),
                new DynamicTypeValueReceiverOnExecutor<>(executor, consumer));
    }

    @NonNull
    @RestrictTo(Scope.LIBRARY_GROUP)
    BoundDynamicTypeImpl bindInternal(
            @NonNull DynamicInstant instantSource,
            @NonNull DynamicTypeValueReceiver<Instant> consumer) {
        List<DynamicDataNode<?>> resultBuilder = new ArrayList<>();
        bindRecursively(
                instantSource, new DynamicTypeValueReceiverOnExecutor<>(consumer), resultBuilder);
        return new BoundDynamicTypeImpl(resultBuilder, mDynamicTypesQuotaManager);
    }

    @NonNull
    BoundDynamicTypeImpl bindInternal(
            @NonNull DynamicBuilders.DynamicBool boolSource,
            @NonNull Executor executor,
            @NonNull DynamicTypeValueReceiver<Boolean> consumer) {
        return bindInternal(
                boolSource.toDynamicBoolProto(),
                new DynamicTypeValueReceiverOnExecutor<>(executor, consumer));
    }

    @NonNull
    @RestrictTo(Scope.LIBRARY_GROUP)
    BoundDynamicTypeImpl bindInternal(
            @NonNull DynamicBool boolSource, @NonNull DynamicTypeValueReceiver<Boolean> consumer) {
        List<DynamicDataNode<?>> resultBuilder = new ArrayList<>();
        bindRecursively(
                boolSource, new DynamicTypeValueReceiverOnExecutor<>(consumer), resultBuilder);
        return new BoundDynamicTypeImpl(resultBuilder, mDynamicTypesQuotaManager);
    }

    /**
     * Same as {@link #bind}, but instead of returning one {@link BoundDynamicType}, all {@link
     * DynamicDataNode} produced by evaluating given dynamic type are added to the given list.
     */
    private void bindRecursively(
            @NonNull DynamicString stringSource,
            @NonNull DynamicTypeValueReceiverWithPreUpdate<String> consumer,
            @NonNull ULocale locale,
            @NonNull List<DynamicDataNode<?>> resultBuilder) {
        DynamicDataNode<?> node;

        switch (stringSource.getInnerCase()) {
            case FIXED:
                node = new FixedStringNode(stringSource.getFixed(), consumer);
                break;
            case INT32_FORMAT_OP:
                {
                    NumberFormatter formatter =
                            new NumberFormatter(stringSource.getInt32FormatOp(), locale);
                    Int32FormatNode int32FormatNode = new Int32FormatNode(formatter, consumer);
                    node = int32FormatNode;
                    bindRecursively(
                            stringSource.getInt32FormatOp().getInput(),
                            int32FormatNode.getIncomingCallback(),
                            resultBuilder);
                    break;
                }
            case FLOAT_FORMAT_OP:
                {
                    NumberFormatter formatter =
                            new NumberFormatter(stringSource.getFloatFormatOp(), locale);
                    FloatFormatNode floatFormatNode = new FloatFormatNode(formatter, consumer);
                    node = floatFormatNode;
                    bindRecursively(
                            stringSource.getFloatFormatOp().getInput(),
                            floatFormatNode.getIncomingCallback(),
                            resultBuilder);
                    break;
                }
            case STATE_SOURCE:
                {
                    DynamicProto.StateStringSource stateSource = stringSource.getStateSource();
                    node =
                           new StateStringNode(
                                   stateSource.getSourceNamespace().isEmpty()
                                           ? mStateStore : mPlatformDataStore,
                                   stateSource,
                                   consumer);
                    break;
                }
            case CONDITIONAL_OP:
                {
                    ConditionalOpNode<String> conditionalNode = new ConditionalOpNode<>(consumer);

                    ConditionalStringOp op = stringSource.getConditionalOp();
                    bindRecursively(
                            op.getCondition(),
                            conditionalNode.getConditionIncomingCallback(),
                            resultBuilder);
                    bindRecursively(
                            op.getValueIfTrue(),
                            conditionalNode.getTrueValueIncomingCallback(),
                            locale,
                            resultBuilder);
                    bindRecursively(
                            op.getValueIfFalse(),
                            conditionalNode.getFalseValueIncomingCallback(),
                            locale,
                            resultBuilder);

                    node = conditionalNode;
                    break;
                }
            case CONCAT_OP:
                {
                    StringConcatOpNode concatNode = new StringConcatOpNode(consumer);
                    node = concatNode;
                    bindRecursively(
                            stringSource.getConcatOp().getInputLhs(),
                            concatNode.getLhsIncomingCallback(),
                            locale,
                            resultBuilder);
                    bindRecursively(
                            stringSource.getConcatOp().getInputRhs(),
                            concatNode.getRhsIncomingCallback(),
                            locale,
                            resultBuilder);
                    break;
                }
            case INNER_NOT_SET:
                throw new IllegalArgumentException("DynamicString has no inner source set");
            default:
                throw new IllegalArgumentException("Unknown DynamicString source type");
        }

        resultBuilder.add(node);
    }

    /**
     * Same as {@link #bind}, but instead of returning one {@link BoundDynamicType}, all {@link
     * DynamicDataNode} produced by evaluating given dynamic type are added to the given list.
     */
    private void bindRecursively(
            @NonNull DynamicInt32 int32Source,
            @NonNull DynamicTypeValueReceiverWithPreUpdate<Integer> consumer,
            @NonNull List<DynamicDataNode<?>> resultBuilder) {
        DynamicDataNode<Integer> node;

        switch (int32Source.getInnerCase()) {
            case FIXED:
                node = new FixedInt32Node(int32Source.getFixed(), consumer);
                break;
            case PLATFORM_SOURCE: {
                node = new LegacyPlatformInt32SourceNode(
                        mPlatformDataStore,
                        int32Source.getPlatformSource(),
                        consumer);
                break;
            }
            case ARITHMETIC_OPERATION:
                {
                    ArithmeticInt32Node arithmeticNode =
                            new ArithmeticInt32Node(int32Source.getArithmeticOperation(), consumer);
                    node = arithmeticNode;

                    bindRecursively(
                            int32Source.getArithmeticOperation().getInputLhs(),
                            arithmeticNode.getLhsIncomingCallback(),
                            resultBuilder);
                    bindRecursively(
                            int32Source.getArithmeticOperation().getInputRhs(),
                            arithmeticNode.getRhsIncomingCallback(),
                            resultBuilder);

                    break;
                }
            case STATE_SOURCE:
                {
                    DynamicProto.StateInt32Source stateSource = int32Source.getStateSource();
                    node = new StateInt32SourceNode(
                            stateSource.getSourceNamespace().isEmpty()
                                    ? mStateStore : mPlatformDataStore,
                            stateSource,
                            consumer);
                    break;
                }
            case CONDITIONAL_OP:
                {
                    ConditionalOpNode<Integer> conditionalNode = new ConditionalOpNode<>(consumer);

                    ConditionalInt32Op op = int32Source.getConditionalOp();
                    bindRecursively(
                            op.getCondition(),
                            conditionalNode.getConditionIncomingCallback(),
                            resultBuilder);
                    bindRecursively(
                            op.getValueIfTrue(),
                            conditionalNode.getTrueValueIncomingCallback(),
                            resultBuilder);
                    bindRecursively(
                            op.getValueIfFalse(),
                            conditionalNode.getFalseValueIncomingCallback(),
                            resultBuilder);

                    node = conditionalNode;
                    break;
                }
            case FLOAT_TO_INT:
                {
                    FloatToInt32Node conversionNode =
                            new FloatToInt32Node(int32Source.getFloatToInt(), consumer);
                    node = conversionNode;

                    bindRecursively(
                            int32Source.getFloatToInt().getInput(),
                            conversionNode.getIncomingCallback(),
                            resultBuilder);
                    break;
                }
            case DURATION_PART:
                {
                    GetDurationPartOpNode durationPartOpNode =
                            new GetDurationPartOpNode(int32Source.getDurationPart(), consumer);
                    node = durationPartOpNode;

                    bindRecursively(
                            int32Source.getDurationPart().getInput(),
                            durationPartOpNode.getIncomingCallback(),
                            resultBuilder);
                    break;
                }
            case ANIMATABLE_FIXED:

                // We don't have to check if enableAnimations is true, because if it's false and
                // we didn't have static value set, constructor has put QuotaManager that don't
                // have any quota, so animations won't be played and they would jump to the end
                // value.
                node =
                        new AnimatableFixedInt32Node(
                                int32Source.getAnimatableFixed(), consumer, mAnimationQuotaManager);
                break;
            case ANIMATABLE_DYNAMIC:
                // We don't have to check if enableAnimations is true, because if it's false and
                // we didn't have static value set, constructor has put QuotaManager that don't
                // have any quota, so animations won't be played and they would jump to the end
                // value.
                AnimatableDynamicInt32 dynamicNode = int32Source.getAnimatableDynamic();
                DynamicAnimatedInt32Node animationNode =
                        new DynamicAnimatedInt32Node(
                                consumer, dynamicNode.getAnimationSpec(), mAnimationQuotaManager);
                node = animationNode;

                bindRecursively(
                        dynamicNode.getInput(), animationNode.getInputCallback(), resultBuilder);
                break;
            case INNER_NOT_SET:
                throw new IllegalArgumentException("DynamicInt32 has no inner source set");
            default:
                throw new IllegalArgumentException("Unknown DynamicInt32 source type");
        }

        resultBuilder.add(node);
    }

    /**
     * Same as {@link #bind}, but instead of returning one {@link BoundDynamicType}, all {@link
     * DynamicDataNode} produced by evaluating given dynamic type are added to the given list.
     */
    private void bindRecursively(
            @NonNull DynamicDuration durationSource,
            @NonNull DynamicTypeValueReceiverWithPreUpdate<Duration> consumer,
            @NonNull List<DynamicDataNode<?>> resultBuilder) {
        DynamicDataNode<?> node;

        switch (durationSource.getInnerCase()) {
            case BETWEEN:
                BetweenInstancesNode betweenInstancesNode = new BetweenInstancesNode(consumer);
                node = betweenInstancesNode;
                bindRecursively(
                        durationSource.getBetween().getStartInclusive(),
                        betweenInstancesNode.getLhsIncomingCallback(),
                        resultBuilder);
                bindRecursively(
                        durationSource.getBetween().getEndExclusive(),
                        betweenInstancesNode.getRhsIncomingCallback(),
                        resultBuilder);
                break;
            case FIXED:
                node = new FixedDurationNode(durationSource.getFixed(), consumer);
                break;
            case CONDITIONAL_OP:
                ConditionalOpNode<Duration> conditionalNode = new ConditionalOpNode<>(consumer);

                ConditionalDurationOp op = durationSource.getConditionalOp();
                bindRecursively(
                        op.getCondition(),
                        conditionalNode.getConditionIncomingCallback(),
                        resultBuilder);
                bindRecursively(
                        op.getValueIfTrue(),
                        conditionalNode.getTrueValueIncomingCallback(),
                        resultBuilder);
                bindRecursively(
                        op.getValueIfFalse(),
                        conditionalNode.getFalseValueIncomingCallback(),
                        resultBuilder);

                node = conditionalNode;
                break;
            case INNER_NOT_SET:
                throw new IllegalArgumentException("DynamicDuration has no inner source set");
            default:
                throw new IllegalArgumentException("Unknown DynamicDuration source type");
        }

        resultBuilder.add(node);
    }

    /**
     * Same as {@link #bind}, but instead of returning one {@link BoundDynamicType}, all {@link
     * DynamicDataNode} produced by evaluating given dynamic type are added to the given list.
     */
    private void bindRecursively(
            @NonNull DynamicInstant instantSource,
            @NonNull DynamicTypeValueReceiverWithPreUpdate<Instant> consumer,
            @NonNull List<DynamicDataNode<?>> resultBuilder) {
        DynamicDataNode<?> node;

        switch (instantSource.getInnerCase()) {
            case FIXED:
                node = new FixedInstantNode(instantSource.getFixed(), consumer);
                break;
            case PLATFORM_SOURCE:
                node = new PlatformTimeSourceNode(mTimeDataSource, consumer);
                break;
            case CONDITIONAL_OP:
                ConditionalOpNode<Instant> conditionalNode = new ConditionalOpNode<>(consumer);

                ConditionalInstantOp op = instantSource.getConditionalOp();
                bindRecursively(
                        op.getCondition(),
                        conditionalNode.getConditionIncomingCallback(),
                        resultBuilder);
                bindRecursively(
                        op.getValueIfTrue(),
                        conditionalNode.getTrueValueIncomingCallback(),
                        resultBuilder);
                bindRecursively(
                        op.getValueIfFalse(),
                        conditionalNode.getFalseValueIncomingCallback(),
                        resultBuilder);

                node = conditionalNode;
                break;

            case INNER_NOT_SET:
                throw new IllegalArgumentException("DynamicInstant has no inner source set");
            default:
                throw new IllegalArgumentException("Unknown DynamicInstant source type");
        }

        resultBuilder.add(node);
    }

    /**
     * Same as {@link #bind}, but instead of returning one {@link BoundDynamicType}, all {@link
     * DynamicDataNode} produced by evaluating given dynamic type are added to the given list.
     */
    private void bindRecursively(
            @NonNull DynamicFloat floatSource,
            @NonNull DynamicTypeValueReceiverWithPreUpdate<Float> consumer,
            @NonNull List<DynamicDataNode<?>> resultBuilder) {
        DynamicDataNode<?> node;

        switch (floatSource.getInnerCase()) {
            case FIXED:
                node = new FixedFloatNode(floatSource.getFixed(), consumer);
                break;
            case STATE_SOURCE:
                {
                    DynamicProto.StateFloatSource stateSource = floatSource.getStateSource();
                    node = new StateFloatSourceNode(
                            stateSource.getSourceNamespace().isEmpty()
                                    ? mStateStore : mPlatformDataStore,
                            stateSource,
                            consumer);
                    break;
                }
            case ARITHMETIC_OPERATION:
                {
                    ArithmeticFloatNode arithmeticNode =
                            new ArithmeticFloatNode(floatSource.getArithmeticOperation(), consumer);
                    node = arithmeticNode;

                    bindRecursively(
                            floatSource.getArithmeticOperation().getInputLhs(),
                            arithmeticNode.getLhsIncomingCallback(),
                            resultBuilder);
                    bindRecursively(
                            floatSource.getArithmeticOperation().getInputRhs(),
                            arithmeticNode.getRhsIncomingCallback(),
                            resultBuilder);

                    break;
                }
            case INT32_TO_FLOAT_OPERATION:
                {
                    Int32ToFloatNode toFloatNode = new Int32ToFloatNode(consumer);
                    node = toFloatNode;

                    bindRecursively(
                            floatSource.getInt32ToFloatOperation().getInput(),
                            toFloatNode.getIncomingCallback(),
                            resultBuilder);
                    break;
                }
            case CONDITIONAL_OP:
                {
                    ConditionalOpNode<Float> conditionalNode = new ConditionalOpNode<>(consumer);

                    ConditionalFloatOp op = floatSource.getConditionalOp();
                    bindRecursively(
                            op.getCondition(),
                            conditionalNode.getConditionIncomingCallback(),
                            resultBuilder);
                    bindRecursively(
                            op.getValueIfTrue(),
                            conditionalNode.getTrueValueIncomingCallback(),
                            resultBuilder);
                    bindRecursively(
                            op.getValueIfFalse(),
                            conditionalNode.getFalseValueIncomingCallback(),
                            resultBuilder);

                    node = conditionalNode;
                    break;
                }
            case ANIMATABLE_FIXED:
                // We don't have to check if enableAnimations is true, because if it's false and
                // we didn't have static value set, constructor has put QuotaManager that don't
                // have any quota, so animations won't be played and they would jump to the end
                // value.
                node =
                        new AnimatableFixedFloatNode(
                                floatSource.getAnimatableFixed(), consumer, mAnimationQuotaManager);
                break;
            case ANIMATABLE_DYNAMIC:
                // We don't have to check if enableAnimations is true, because if it's false and
                // we didn't have static value set, constructor has put QuotaManager that don't
                // have any quota, so animations won't be played and they would jump to the end
                // value.
                AnimatableDynamicFloat dynamicNode = floatSource.getAnimatableDynamic();
                DynamicAnimatedFloatNode animationNode =
                        new DynamicAnimatedFloatNode(
                                consumer, dynamicNode.getAnimationSpec(), mAnimationQuotaManager);
                node = animationNode;

                bindRecursively(
                        dynamicNode.getInput(), animationNode.getInputCallback(), resultBuilder);
                break;

            case INNER_NOT_SET:
                throw new IllegalArgumentException("DynamicFloat has no inner source set");
            default:
                throw new IllegalArgumentException("Unknown DynamicFloat source type");
        }

        resultBuilder.add(node);
    }

    /**
     * Same as {@link #bind}, but instead of returning one {@link BoundDynamicType}, all {@link
     * DynamicDataNode} produced by evaluating given dynamic type are added to the given list.
     */
    private void bindRecursively(
            @NonNull DynamicColor colorSource,
            @NonNull DynamicTypeValueReceiverWithPreUpdate<Integer> consumer,
            @NonNull List<DynamicDataNode<?>> resultBuilder) {
        DynamicDataNode<?> node;

        switch (colorSource.getInnerCase()) {
            case FIXED:
                node = new FixedColorNode(colorSource.getFixed(), consumer);
                break;
            case STATE_SOURCE:
                DynamicProto.StateColorSource stateSource = colorSource.getStateSource();
                node = new StateColorSourceNode(
                        stateSource.getSourceNamespace().isEmpty()
                                ? mStateStore : mPlatformDataStore,
                        stateSource,
                        consumer);
                break;
            case ANIMATABLE_FIXED:
                // We don't have to check if enableAnimations is true, because if it's false and
                // we didn't have static value set, constructor has put QuotaManager that don't
                // have any quota, so animations won't be played and they would jump to the end
                // value.
                node =
                        new AnimatableFixedColorNode(
                                colorSource.getAnimatableFixed(), consumer, mAnimationQuotaManager);
                break;
            case ANIMATABLE_DYNAMIC:
                // We don't have to check if enableAnimations is true, because if it's false and
                // we didn't have static value set, constructor has put QuotaManager that don't
                // have any quota, so animations won't be played and they would jump to the end
                // value.
                AnimatableDynamicColor dynamicNode = colorSource.getAnimatableDynamic();
                DynamicAnimatedColorNode animationNode =
                        new DynamicAnimatedColorNode(
                                consumer, dynamicNode.getAnimationSpec(), mAnimationQuotaManager);
                node = animationNode;

                bindRecursively(
                        dynamicNode.getInput(), animationNode.getInputCallback(), resultBuilder);
                break;
            case CONDITIONAL_OP:
                ConditionalOpNode<Integer> conditionalNode = new ConditionalOpNode<>(consumer);

                ConditionalColorOp op = colorSource.getConditionalOp();
                bindRecursively(
                        op.getCondition(),
                        conditionalNode.getConditionIncomingCallback(),
                        resultBuilder);
                bindRecursively(
                        op.getValueIfTrue(),
                        conditionalNode.getTrueValueIncomingCallback(),
                        resultBuilder);
                bindRecursively(
                        op.getValueIfFalse(),
                        conditionalNode.getFalseValueIncomingCallback(),
                        resultBuilder);

                node = conditionalNode;
                break;
            case INNER_NOT_SET:
                throw new IllegalArgumentException("DynamicColor has no inner source set");
            default:
                throw new IllegalArgumentException("Unknown DynamicColor source type");
        }

        resultBuilder.add(node);
    }

    /**
     * Same as {@link #bind}, but instead of returning one {@link BoundDynamicType}, all {@link
     * DynamicDataNode} produced by evaluating given dynamic type are added to the given list.
     */
    private void bindRecursively(
            @NonNull DynamicBool boolSource,
            @NonNull DynamicTypeValueReceiverWithPreUpdate<Boolean> consumer,
            @NonNull List<DynamicDataNode<?>> resultBuilder) {
        DynamicDataNode<?> node;

        switch (boolSource.getInnerCase()) {
            case FIXED:
                node = new FixedBoolNode(boolSource.getFixed(), consumer);
                break;
            case STATE_SOURCE:
                {
                    DynamicProto.StateBoolSource stateSource = boolSource.getStateSource();
                    node = new StateBoolNode(
                            stateSource.getSourceNamespace().isEmpty()
                                    ? mStateStore : mPlatformDataStore,
                            stateSource,
                            consumer);
                    break;
                }
            case INT32_COMPARISON:
                {
                    ComparisonInt32Node compNode =
                            new ComparisonInt32Node(boolSource.getInt32Comparison(), consumer);
                    node = compNode;

                    bindRecursively(
                            boolSource.getInt32Comparison().getInputLhs(),
                            compNode.getLhsIncomingCallback(),
                            resultBuilder);
                    bindRecursively(
                            boolSource.getInt32Comparison().getInputRhs(),
                            compNode.getRhsIncomingCallback(),
                            resultBuilder);

                    break;
                }
            case LOGICAL_OP:
                {
                    LogicalBoolOp logicalNode =
                            new LogicalBoolOp(boolSource.getLogicalOp(), consumer);
                    node = logicalNode;

                    bindRecursively(
                            boolSource.getLogicalOp().getInputLhs(),
                            logicalNode.getLhsIncomingCallback(),
                            resultBuilder);
                    bindRecursively(
                            boolSource.getLogicalOp().getInputRhs(),
                            logicalNode.getRhsIncomingCallback(),
                            resultBuilder);

                    break;
                }
            case NOT_OP:
                {
                    NotBoolOp notNode = new NotBoolOp(consumer);
                    node = notNode;
                    bindRecursively(
                            boolSource.getNotOp().getInput(),
                            notNode.getIncomingCallback(),
                            resultBuilder);
                    break;
                }
            case FLOAT_COMPARISON:
                {
                    ComparisonFloatNode compNode =
                            new ComparisonFloatNode(boolSource.getFloatComparison(), consumer);
                    node = compNode;

                    bindRecursively(
                            boolSource.getFloatComparison().getInputLhs(),
                            compNode.getLhsIncomingCallback(),
                            resultBuilder);
                    bindRecursively(
                            boolSource.getFloatComparison().getInputRhs(),
                            compNode.getRhsIncomingCallback(),
                            resultBuilder);

                    break;
                }
            case INNER_NOT_SET:
                throw new IllegalArgumentException("DynamicBool has no inner source set");
            default:
                throw new IllegalArgumentException("Unknown DynamicBool source type");
        }

        resultBuilder.add(node);
    }

    /**
     * Wraps {@link DynamicTypeValueReceiver} and executes its methods on the given {@link
     * Executor}.
     */
    private static class DynamicTypeValueReceiverOnExecutor<T>
            implements DynamicTypeValueReceiverWithPreUpdate<T> {

        @NonNull private final Executor mExecutor;
        @NonNull private final DynamicTypeValueReceiver<T> mConsumer;

        DynamicTypeValueReceiverOnExecutor(@NonNull DynamicTypeValueReceiver<T> consumer) {
            this(Runnable::run, consumer);
        }

        DynamicTypeValueReceiverOnExecutor(
                @NonNull Executor executor, @NonNull DynamicTypeValueReceiver<T> consumer) {
            this.mConsumer = consumer;
            this.mExecutor = executor;
        }

        /** This method is noop in this class. */
        @Override
        @SuppressWarnings("ExecutorTaskName")
        public void onPreUpdate() {}

        @Override
        @SuppressWarnings("ExecutorTaskName")
        public void onData(@NonNull T newData) {
            mExecutor.execute(() -> mConsumer.onData(newData));
        }

        @Override
        @SuppressWarnings("ExecutorTaskName")
        public void onInvalidated() {
            mExecutor.execute(mConsumer::onInvalidated);
        }
    }
}

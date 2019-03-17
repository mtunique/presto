/*
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
package com.facebook.presto.sql.planner.optimizations;

import com.facebook.presto.spi.LocalProperty;
import com.facebook.presto.sql.planner.Partitioning;
import com.facebook.presto.sql.planner.Symbol;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import javax.annotation.concurrent.Immutable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

class PreferredProperties
{
    private final Optional<Global> globalProperties;
    private final List<LocalProperty<Symbol>> localProperties; // mt: {A1, A2, A3}

    private PreferredProperties(
            Optional<Global> globalProperties,
            List<? extends LocalProperty<Symbol>> localProperties)
    {
        requireNonNull(globalProperties, "globalProperties is null");
        requireNonNull(localProperties, "localProperties is null");

        this.globalProperties = globalProperties;
        this.localProperties = ImmutableList.copyOf(localProperties);
    }

    public static PreferredProperties any() // p7 non-partitioned 没放在 global 的prop中表示 而是 global prop 为 null，不会有global 是 null prop 不是null的
    {
        return builder().build();
    }

    public static PreferredProperties undistributed()
    {
        return builder()
                .global(Global.undistributed())
                .build();
    }

    public static PreferredProperties partitioned(Set<Symbol> columns)
    {
        return builder()
                .global(Global.distributed(PartitioningProperties.partitioned(columns)))
                .build();
    }

    public static PreferredProperties partitionedWithNullsAndAnyReplicated(Set<Symbol> columns)
    {
        return builder()
                .global(Global.distributed(PartitioningProperties.partitioned(columns).withNullsAndAnyReplicated(true)))
                .build();
    }

    public static PreferredProperties distributed()
    {
        return builder()
                .global(Global.distributed())
                .build();
    }

    public static PreferredProperties partitioned(Partitioning partitioning)
    {
        return builder()
                .global(Global.distributed(PartitioningProperties.partitioned(partitioning)))
                .build();
    }

    public static PreferredProperties partitionedWithNullsAndAnyReplicated(Partitioning partitioning)
    {
        return builder()
                .global(Global.distributed(PartitioningProperties.partitioned(partitioning).withNullsAndAnyReplicated(true)))
                .build();
    }

    public static PreferredProperties partitionedWithLocal(Set<Symbol> columns, List<? extends LocalProperty<Symbol>> localProperties)
    {
        return builder()
                .global(Global.distributed(PartitioningProperties.partitioned(columns)))
                .local(localProperties)
                .build();
    }

    public static PreferredProperties undistributedWithLocal(List<? extends LocalProperty<Symbol>> localProperties)
    {
        return builder()
                .global(Global.undistributed())
                .local(localProperties)
                .build();
    }

    public static PreferredProperties local(List<? extends LocalProperty<Symbol>> localProperties)
    {
        return builder()
                .local(localProperties)
                .build();
    }

    public Optional<Global> getGlobalProperties()
    {
        return globalProperties;
    }

    public List<LocalProperty<Symbol>> getLocalProperties()
    {
        return localProperties;
    }

    public PreferredProperties mergeWithParent(PreferredProperties parent)
    {
        List<LocalProperty<Symbol>> newLocal = ImmutableList.<LocalProperty<Symbol>>builder()
                .addAll(localProperties)
                .addAll(parent.getLocalProperties())
                .build();

        Builder builder = builder()
                .local(newLocal);

        if (globalProperties.isPresent()) {
            Global currentGlobal = globalProperties.get();
            Global newGlobal = parent.getGlobalProperties()
                    .map(currentGlobal::mergeWithParent)
                    .orElse(currentGlobal);
            builder.global(newGlobal);
        }
        else {
            builder.global(parent.getGlobalProperties());
        }

        return builder.build();
    }

    public PreferredProperties translate(Function<Symbol, Optional<Symbol>> translator)
    {
        Optional<Global> newGlobalProperties = globalProperties.map(global -> global.translate(translator));
        List<LocalProperty<Symbol>> newLocalProperties = LocalProperties.translate(localProperties, translator);
        return new PreferredProperties(newGlobalProperties, newLocalProperties);
    }

    public static Builder builder()
    {
        return new Builder();
    }

    public static class Builder
    {
        private Optional<Global> globalProperties = Optional.empty();
        private List<LocalProperty<Symbol>> localProperties = ImmutableList.of();

        public Builder global(Global globalProperties)
        {
            this.globalProperties = Optional.of(globalProperties);
            return this;
        }

        public Builder global(Optional<Global> globalProperties)
        {
            this.globalProperties = globalProperties;
            return this;
        }

        public Builder global(PreferredProperties other)
        {
            this.globalProperties = other.globalProperties;
            return this;
        }

        public Builder local(List<? extends LocalProperty<Symbol>> localProperties)
        {
            this.localProperties = ImmutableList.copyOf(localProperties);
            return this;
        }

        public Builder local(PreferredProperties other)
        {
            this.localProperties = ImmutableList.copyOf(other.localProperties);
            return this;
        }

        public PreferredProperties build()
        {
            return new PreferredProperties(globalProperties, localProperties);
        }
    }

    @Immutable
    public static final class Global
    {
        private final boolean distributed;
        private final Optional<PartitioningProperties> partitioningProperties; // if missing => partitioned with some unknown scheme

        private Global(boolean distributed, Optional<PartitioningProperties> partitioningProperties)
        {
            this.distributed = distributed;
            this.partitioningProperties = requireNonNull(partitioningProperties, "partitioningProperties is null");
        }

        public static Global undistributed()
        {
            return new Global(false, Optional.of(PartitioningProperties.singlePartition()));
        }

        public static Global distributed(Optional<PartitioningProperties> partitioningProperties)
        {
            return new Global(true, partitioningProperties);
        }

        public static Global distributed()
        {
            return distributed(Optional.empty());
        }

        public static Global distributed(PartitioningProperties partitioning)
        {
            return distributed(Optional.of(partitioning));
        }

        public boolean isDistributed()
        {
            return distributed;
        }

        public Optional<PartitioningProperties> getPartitioningProperties()
        {
            return partitioningProperties;
        }

        public Global mergeWithParent(Global parent) // 总归以当前为准
        {
            if (distributed != parent.distributed) {
                return this; // 不同 以当前为准
            }
            if (!partitioningProperties.isPresent()) {
                return parent; // 没有就以 parent 为准
            }
            if (!parent.partitioningProperties.isPresent()) {
                return this; // parent 没有就以当前为准
            }
            return new Global(distributed, Optional.of(partitioningProperties.get().mergeWithParent(parent.partitioningProperties.get())));
        }

        public Global translate(Function<Symbol, Optional<Symbol>> translator)
        {
            if (!isDistributed()) {
                return this;
            }
            return distributed(partitioningProperties.flatMap(properties -> properties.translate(translator)));
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(distributed, partitioningProperties);
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            final Global other = (Global) obj;
            return Objects.equals(this.distributed, other.distributed)
                    && Objects.equals(this.partitioningProperties, other.partitioningProperties);
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("distributed", distributed)
                    .add("partitioningProperties", partitioningProperties)
                    .toString();
        }
    }

    @Immutable
    public static final class PartitioningProperties
    {
        private final Set<Symbol> partitioningColumns;
        private final Optional<Partitioning> partitioning; // Specific partitioning requested
        private final boolean nullsAndAnyReplicated;  // mt: ???

        private PartitioningProperties(Set<Symbol> partitioningColumns, Optional<Partitioning> partitioning, boolean nullsAndAnyReplicated)
        {
            this.partitioningColumns = ImmutableSet.copyOf(requireNonNull(partitioningColumns, "partitioningColumns is null"));
            this.partitioning = requireNonNull(partitioning, "function is null");
            this.nullsAndAnyReplicated = nullsAndAnyReplicated;

            checkArgument(!partitioning.isPresent() || partitioning.get().getColumns().equals(partitioningColumns), "Partitioning input must match partitioningColumns");
        }

        public PartitioningProperties withNullsAndAnyReplicated(boolean nullsAndAnyReplicated)
        {
            return new PartitioningProperties(partitioningColumns, partitioning, nullsAndAnyReplicated);
        }

        public static PartitioningProperties partitioned(Partitioning partitioning)
        {
            return new PartitioningProperties(partitioning.getColumns(), Optional.of(partitioning), false);
        }

        public static PartitioningProperties partitioned(Set<Symbol> columns)
        {
            return new PartitioningProperties(columns, Optional.empty(), false);
        }

        public static PartitioningProperties singlePartition()
        {
            return partitioned(ImmutableSet.of());
        }

        public Set<Symbol> getPartitioningColumns()
        {
            return partitioningColumns;
        }

        public Optional<Partitioning> getPartitioning()
        {
            return partitioning;
        }

        public boolean isNullsAndAnyReplicated()
        {
            return nullsAndAnyReplicated;
        }

        public PartitioningProperties mergeWithParent(PartitioningProperties parent)
        {
            // Non-negotiable if we require a specific partitioning
            if (partitioning.isPresent()) {
                return this;
            }

            // Partitioning with different replication cannot be compared
            if (nullsAndAnyReplicated != parent.nullsAndAnyReplicated) {
                return this;
            }

            if (parent.partitioning.isPresent()) {
                // If the parent has a partitioning preference, propagate parent only if the parent's partitioning columns satisfies our preference.
                // Otherwise, ignore the parent since the parent will have to repartition anyways. // mt: 如果冲突（两个不兼容，this 的 没有全部包含 parent 的），就取this，忽略 parent 的 之后会enforce的
                return partitioningColumns.containsAll(parent.partitioningColumns) ? parent : this;
            }

            // Otherwise partition on any common columns if available
            Set<Symbol> common = Sets.intersection(partitioningColumns, parent.partitioningColumns); // mt: 取交集
            return common.isEmpty() ? this : partitioned(common).withNullsAndAnyReplicated(nullsAndAnyReplicated);
        }

        public Optional<PartitioningProperties> translate(Function<Symbol, Optional<Symbol>> translator)
        {
            Set<Symbol> newPartitioningColumns = partitioningColumns.stream()
                    .map(translator)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .collect(toImmutableSet());

            // Translation fails if we have prior partitioning columns and none could be translated
            if (!partitioningColumns.isEmpty() && newPartitioningColumns.isEmpty()) {
                return Optional.empty();
            }

            if (!partitioning.isPresent()) {
                return Optional.of(new PartitioningProperties(newPartitioningColumns, Optional.empty(), nullsAndAnyReplicated));
            }

            Optional<Partitioning> newPartitioning = partitioning.get().translate(new Partitioning.Translator(translator, symbol -> Optional.empty(), coalesceSymbols -> Optional.empty()));
            if (!newPartitioning.isPresent()) {
                return Optional.empty();
            }

            return Optional.of(new PartitioningProperties(newPartitioningColumns, newPartitioning, nullsAndAnyReplicated));
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(partitioningColumns, partitioning, nullsAndAnyReplicated);
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            final PartitioningProperties other = (PartitioningProperties) obj;
            return Objects.equals(this.partitioningColumns, other.partitioningColumns)
                    && Objects.equals(this.partitioning, other.partitioning)
                    && this.nullsAndAnyReplicated == other.nullsAndAnyReplicated;
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("partitioningColumns", partitioningColumns)
                    .add("partitioning", partitioning)
                    .add("nullsAndAnyReplicated", nullsAndAnyReplicated)
                    .toString();
        }
    }
}

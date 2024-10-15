/*
 * Copyright 2019 Santanu Sinha <santanu.sinha@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.appform.dropwizard.sharding;

import com.google.common.annotations.VisibleForTesting;
import io.appform.dropwizard.sharding.caching.LookupCache;
import io.appform.dropwizard.sharding.caching.RelationalCache;
import io.appform.dropwizard.sharding.config.MetricConfig;
import io.appform.dropwizard.sharding.config.ShardedHibernateFactory;
import io.appform.dropwizard.sharding.config.ShardedHibernateFactoryConfigProvider;
import io.appform.dropwizard.sharding.dao.CacheableLookupDao;
import io.appform.dropwizard.sharding.dao.CacheableRelationalDao;
import io.appform.dropwizard.sharding.dao.LookupDao;
import io.appform.dropwizard.sharding.dao.RelationalDao;
import io.appform.dropwizard.sharding.dao.WrapperDao;
import io.appform.dropwizard.sharding.filters.TransactionFilter;
import io.appform.dropwizard.sharding.listeners.TransactionListener;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import io.appform.dropwizard.sharding.sharding.BucketIdExtractor;
import io.appform.dropwizard.sharding.sharding.ShardBlacklistingStore;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.dropwizard.Configuration;
import io.dropwizard.ConfiguredBundle;
import io.dropwizard.hibernate.AbstractDAO;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Base for bundles. This cannot be used by clients. Use one of the derived classes.
 */
@Slf4j
public abstract class DBShardingBundleBase<T extends Configuration> implements ConfiguredBundle<T> {

    public static final String DEFAULT_NAMESPACE = "default";

    private final MultiTenantDBShardingBundleBase<T> delegate;

    @Getter
    private final String dbNamespace;


    protected DBShardingBundleBase(Class<?> entity, Class<?>... entities) {
        this(DEFAULT_NAMESPACE, entity, entities);
    }

    protected DBShardingBundleBase(String... classPathPrefixes) {
        this(DEFAULT_NAMESPACE, Arrays.asList(classPathPrefixes));
    }

    protected DBShardingBundleBase(
            String dbNamespace,
            Class<?> entity,
            Class<?>... entities) {
        this.dbNamespace = dbNamespace;
        this.delegate = new MultiTenantDBShardingBundleBase<T>(entity, entities) {
            @Override
            protected ShardManager createShardManager(int numShards, ShardBlacklistingStore blacklistingStore) {
                return DBShardingBundleBase.this.createShardManager(numShards, blacklistingStore);
            }

            @Override
            protected ShardedHibernateFactoryConfigProvider getConfigProvider(T config) {
                return new ShardedHibernateFactoryConfigProvider() {
                    @Override
                    public ShardedHibernateFactory getForTenant(String tenantId) {
                        return getConfig(config);
                    }

                    @Override
                    public Map<String, ShardedHibernateFactory> listAll() {
                        return Map.of(dbNamespace, getConfig(config));
                    }
                };
            }
        };
    }

    protected DBShardingBundleBase(String dbNamespace, List<String> classPathPrefixList) {
        this.dbNamespace = dbNamespace;
        this.delegate = new MultiTenantDBShardingBundleBase<T>(classPathPrefixList) {
            @Override
            protected ShardManager createShardManager(int numShards, ShardBlacklistingStore blacklistingStore) {
                return DBShardingBundleBase.this.createShardManager(numShards, blacklistingStore);
            }

            @Override
            protected ShardedHibernateFactoryConfigProvider getConfigProvider(T config) {
                return new ShardedHibernateFactoryConfigProvider() {
                    @Override
                    public ShardedHibernateFactory getForTenant(String tenantId) {
                        return getConfig(config);
                    }

                    @Override
                    public Map<String, ShardedHibernateFactory> listAll() {
                        return Map.of(dbNamespace, getConfig(config));
                    }
                };
            }
        };
    }


    protected abstract ShardManager createShardManager(int numShards, ShardBlacklistingStore blacklistingStore);

    @Override
    public void run(T configuration, Environment environment) {
        delegate.run(configuration, environment);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void initialize(Bootstrap<?> bootstrap) {
        delegate.initialize(bootstrap);
    }

    @VisibleForTesting
    public void runBundles(T configuration, Environment environment) {
        delegate.runBundles(configuration, environment);
    }

    @VisibleForTesting
    public void initBundles(Bootstrap bootstrap) {
        delegate.initBundles(bootstrap);
    }

    @VisibleForTesting
    public Map<Integer, Boolean> healthStatus() {
        return delegate.healthStatus().get(dbNamespace);
    }

    protected abstract ShardedHibernateFactory getConfig(T config);

    protected Supplier<MetricConfig> getMetricConfig(T config) {
        return () -> getConfig(config).getMetricConfig();
    }

    public <EntityType, T extends Configuration>
    LookupDao<EntityType> createParentObjectDao(Class<EntityType> clazz) {
        return new LookupDao<>(dbNamespace, delegate.createParentObjectDao(clazz));
    }

    public <EntityType, T extends Configuration>
    CacheableLookupDao<EntityType> createParentObjectDao(
            Class<EntityType> clazz,
            LookupCache<EntityType> cacheManager) {
        return new CacheableLookupDao<>(dbNamespace,
                delegate.createParentObjectDao(clazz, Map.of(dbNamespace, cacheManager)));
    }

    public <EntityType, T extends Configuration>
    LookupDao<EntityType> createParentObjectDao(
            Class<EntityType> clazz,
            BucketIdExtractor<String> bucketIdExtractor) {
        return new LookupDao<>(dbNamespace,
                delegate.createParentObjectDao(clazz, bucketIdExtractor));
    }

    public <EntityType, T extends Configuration>
    CacheableLookupDao<EntityType> createParentObjectDao(
            Class<EntityType> clazz,
            BucketIdExtractor<String> bucketIdExtractor,
            LookupCache<EntityType> cacheManager) {
        return new CacheableLookupDao<>(dbNamespace,
                delegate.createParentObjectDao(clazz, bucketIdExtractor,
                        Map.of(dbNamespace, cacheManager)));
    }


    public <EntityType, T extends Configuration>
    RelationalDao<EntityType> createRelatedObjectDao(Class<EntityType> clazz) {
        return new RelationalDao<>(dbNamespace,
                delegate.createRelatedObjectDao(clazz));
    }


    public <EntityType, T extends Configuration>
    CacheableRelationalDao<EntityType> createRelatedObjectDao(
            Class<EntityType> clazz,
            RelationalCache<EntityType> cacheManager) {
        return new CacheableRelationalDao<>(dbNamespace,
                delegate.createRelatedObjectDao(clazz, Map.of(dbNamespace, cacheManager)));
    }


    public <EntityType, T extends Configuration>
    RelationalDao<EntityType> createRelatedObjectDao(
            Class<EntityType> clazz,
            BucketIdExtractor<String> bucketIdExtractor) {
        return new RelationalDao<>(dbNamespace,
                delegate.createRelatedObjectDao(clazz, bucketIdExtractor));
    }

    public <EntityType, T extends Configuration>
    CacheableRelationalDao<EntityType> createRelatedObjectDao(
            Class<EntityType> clazz,
            BucketIdExtractor<String> bucketIdExtractor,
            RelationalCache<EntityType> cacheManager) {
        return new CacheableRelationalDao<>(dbNamespace,
                delegate.createRelatedObjectDao(clazz, bucketIdExtractor,
                        Map.of(dbNamespace, cacheManager)));
    }


    public <EntityType, DaoType extends AbstractDAO<EntityType>, T extends Configuration>
    WrapperDao<EntityType, DaoType> createWrapperDao(Class<DaoType> daoTypeClass) {
        return delegate.createWrapperDao(dbNamespace, daoTypeClass);
    }

    public <EntityType, DaoType extends AbstractDAO<EntityType>, T extends Configuration>
    WrapperDao<EntityType, DaoType> createWrapperDao(
            Class<DaoType> daoTypeClass,
            BucketIdExtractor<String> bucketIdExtractor) {
        return delegate.createWrapperDao(dbNamespace, daoTypeClass, bucketIdExtractor);
    }

    public <EntityType, DaoType extends AbstractDAO<EntityType>, T extends Configuration>
    WrapperDao<EntityType, DaoType> createWrapperDao(
            Class<DaoType> daoTypeClass,
            Class[] extraConstructorParamClasses,
            Class[] extraConstructorParamObjects) {
        return delegate.createWrapperDao(dbNamespace, daoTypeClass, extraConstructorParamClasses, extraConstructorParamObjects);
    }

    final ShardManager getShardManager() {
        return delegate.getShardManagers().get(dbNamespace);
    }

    public void registerObserver(TransactionObserver transactionObserver){
        delegate.registerObserver(transactionObserver);
    }

    public void registerListener(TransactionListener transactionListener){
        delegate.registerListener(transactionListener);
    }

    public void registerFilter(TransactionFilter transactionFilter){
        delegate.registerFilter(transactionFilter);
    }
}

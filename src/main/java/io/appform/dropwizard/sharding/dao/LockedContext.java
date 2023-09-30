package io.appform.dropwizard.sharding.dao;

import com.google.common.collect.Lists;
import io.appform.dropwizard.sharding.ShardInfoProvider;
import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import io.appform.dropwizard.sharding.query.QuerySpec;
import io.appform.dropwizard.sharding.utils.TransactionHandler;
import lombok.Getter;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.DetachedCriteria;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The `LockedContext` class encapsulates the context for locked operations on an entity in a specific shard.
 * It provides various methods for applying operations and actions to the entity within the context of a transaction.
 * This context can be used for both reading and inserting entities.
 *
 * @param <T> The type of the entity on which the operations are performed.
 */
@Getter
public class LockedContext<T> {
    @FunctionalInterface
    public interface Mutator<T> {
        void mutator(T parent);
    }

    enum Mode {READ, INSERT}

    private final int shardId;
    private final SessionFactory sessionFactory;
    private final List<Function<T, Void>> operations = Lists.newArrayList();
    private Supplier<T> getter;
    private Function<T, T> saver;
    private T entity;
    private final Mode mode;
    private final TransactionExecutionContext executionContext;
    private final TransactionObserver observer;

    /**
     * Constructs a LockedContext for reading a specific entity within a locked transaction.
     *
     * This constructor initializes a LockedContext, which provides a locked environment for reading a specific entity
     * within a transaction. The LockedContext allows you to retrieve the entity while ensuring that the read operation
     * is performed atomically and under the protection of the locking mechanism.
     *
     * @param shardId The identifier of the shard where the entity is located.
     * @param sessionFactory The Hibernate SessionFactory associated with the entity.
     * @param getter A supplier function responsible for retrieving the entity within the locked context.
     * @param entityClass The Class representing the type of the entity.
     * @param shardInfoProvider A provider for shard-specific information.
     * @param observer An observer for monitoring transaction events.
     */
    public LockedContext(
            int shardId,
            SessionFactory sessionFactory,
            Supplier<T> getter,
            Class<T> entityClass,
            ShardInfoProvider shardInfoProvider,
            TransactionObserver observer) {
        this.shardId = shardId;
        this.sessionFactory = sessionFactory;
        this.getter = getter;
        this.observer = observer;
        this.mode = Mode.READ;
        this.executionContext = buildExecutionContext(shardInfoProvider, entityClass);
    }

    /**
     * Constructs a LockedContext for performing operations on a specific entity within a locked transaction.
     *
     * This constructor initializes a LockedContext, which provides a locked environment for performing operations on a
     * specific entity within a transaction. The LockedContext allows you to work with the entity while ensuring that
     * modifications are made atomically and under the protection of the locking mechanism.
     *
     * @param shardId The identifier of the shard where the entity is located.
     * @param sessionFactory The Hibernate SessionFactory associated with the entity.
     * @param saver A function responsible for saving or updating the entity within the context.
     * @param entity The entity on which operations will be performed within the locked context.
     * @param entityClass The Class representing the type of the entity.
     * @param shardInfoProvider A provider for shard-specific information.
     * @param observer An observer for monitoring transaction events.
     */
    public LockedContext(
            int shardId,
            SessionFactory sessionFactory,
            Function<T, T> saver,
            T entity,
            Class<T> entityClass,
            ShardInfoProvider shardInfoProvider,
            TransactionObserver observer) {
        this.shardId = shardId;
        this.sessionFactory = sessionFactory;
        this.saver = saver;
        this.entity = entity;
        this.observer = observer;
        this.mode = Mode.INSERT;
        this.executionContext = buildExecutionContext(shardInfoProvider, entityClass);
    }
    /**
     * Applies a mutation operation to the current context using a provided mutator.
     *
     * @param <T> The type parameter representing the parent entity.
     * @param mutator The mutator responsible for applying the mutation operation to the context.
     * @return A reference to this LockedContext, enabling method chaining.
     *
     * <p>
     * This method allows the application of a mutation operation to the current context using a provided mutator.
     * The mutator is responsible for specifying the mutation logic to be applied to the context.
     * </p>
     *
     * <p>
     * The {@code mutator} parameter represents an instance of a {@link Mutator} interface, which defines the
     * mutation logic to be executed on the context. The {@link Mutator#mutate(LockedContext)} method of the
     * mutator is invoked, allowing custom mutations to be performed on the context.
     * </p>
     *
     * <p>
     * After the mutation operation is applied, this method returns a reference to the current LockedContext, enabling
     * method chaining or further operations on the modified context.
     * </p>
     */
    public LockedContext<T> mutate(Mutator<T> mutator) {
        return apply(parent -> {
            mutator.mutator(parent);
            return null;
        });
    }

    /**
     * Applies a handler function to the current entity within a locked context.
     *
     * This method allows you to apply a handler function to the current entity within the context of a locked transaction.
     * The handler function is provided as a {@code Function}
     * The handler is added to a list of operations to be executed within the locked context.
     *
     * @param <T> The type parameter representing the parent entity.
     * @param handler The handler function to apply to the current entity.
     * @return A locked context for the current entity type, allowing for further chained operations within a locked transaction.
     */
    public LockedContext<T> apply(Function<T, Void> handler) {
        this.operations.add(handler);
        return this;
    }

    /**
     * Generates entity of type {@code U} using entityGenerator and then persists them
     *
     * @param <U>             The type of the associated entity to be saved.
     * @param relationalDao   The relational DAO responsible for saving the associated entity.
     * @param entityGenerator A function that generates the associated entity based on the parent entity.
     * @return A reference to this LockedContext, enabling method chaining.
     * @throws RuntimeException         if an exception occurs during entity generation or saving.
     *                                  This exception typically wraps any underlying exceptions that may occur
     *                                  during the execution of the entity generation or save operation.
     *                                  It indicates that the save operation was unsuccessful.
     * @throws IllegalArgumentException if the provided relational DAO or entity generator function is null.
     *                                  This exception indicates invalid or missing inputs.
     */
    public <U> LockedContext<T> save(RelationalDao<U> relationalDao, Function<T, U> entityGenerator) {
        return apply(parent -> {
            try {
                U entity = entityGenerator.apply(parent);
                relationalDao.save(this, entity);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    /**
     * Generates list of entity of type {@code U} using entityGenerator and then persists them in bulk
     *
     * @param <U>             The type of the associated entity to be saved.
     * @param relationalDao   The relational DAO responsible for saving the associated entity.
     * @param entityGenerator A function that generates the associated entity based on the parent entity.
     * @return A reference to this LockedContext, enabling method chaining.
     * @throws RuntimeException         if an exception occurs during entity generation or saving.
     *                                  This exception typically wraps any underlying exceptions that may occur
     *                                  during the execution of the entity generation or save operation.
     *                                  It indicates that the save operation was unsuccessful.
     * @throws IllegalArgumentException if the provided relational DAO or entity generator function is null.
     *                                  This exception indicates invalid or missing inputs.
     */
    public <U> LockedContext<T> saveAll(RelationalDao<U> relationalDao, Function<T, List<U>> entityGenerator) {
        return apply(parent -> {
            try {
                List<U> entities = entityGenerator.apply(parent);
                for (U entity : entities) {
                    relationalDao.save(this, entity);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    /**
     * Initiates an update operation using a query against a related RelationalDao within a locked context.
     *
     * This method allows you to initiate an update operation using a query against a related {@code RelationalDao} within the
     * context of a locked transaction. It takes a {@code RelationalDao} instance and an {@code UpdateOperationMeta} object to specify
     * the details of the update operation. The update operation is executed within the locked context provided by this
     * method.
     *
     * @param <U> The type parameter representing the entity type of the related {@code RelationalDao}.
     * @param relationalDao The related {@code RelationalDao} used to perform the update operation.
     * @param updateOperationMeta The {@code UpdateOperationMeta} containing details of the update operation to be executed.
     * @return A locked context for the current entity type, allowing for further chained operations within a locked transaction.
     * @throws RuntimeException If an error occurs during the update operation or query execution.
     */
    public <U> LockedContext<T> updateUsingQuery(
            RelationalDao<U> relationalDao,
            UpdateOperationMeta updateOperationMeta) {
        return apply(parent -> {
            try {
                relationalDao.updateUsingQuery(this, updateOperationMeta);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    /**
     * Saves an associated entity using a relational DAO, an associated entity instance, and a handler function.
     *
     * @param <U>           The type of the associated entity to be saved.
     * @param relationalDao The relational DAO responsible for saving the associated entity.
     * @param entity        The associated entity instance to be saved.
     * @param handler       A handler function that can modify the associated entity before saving.
     * @return A reference to this LockedContext, enabling method chaining.
     * @throws RuntimeException         if an exception occurs during the save operation or if the handler function
     *                                  throws an exception. This exception typically wraps any underlying exceptions
     *                                  that may occur during the execution of the save operation or the handler function.
     *                                  It indicates that the save operation was unsuccessful.
     * @throws IllegalArgumentException if the provided relational DAO or associated entity is null. This exception
     *                                  indicates invalid or missing inputs.
     */
    public <U> LockedContext<T> save(RelationalDao<U> relationalDao, U entity, Function<U, U> handler) {
        return apply(parent -> {
            try {
                relationalDao.save(this, entity, handler);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    /**
     * Updates an associated entity using a relational DAO, an identifier, and a handler function.
     *
     * @param <U>           The type of the associated entity to be updated.
     * @param relationalDao The relational DAO responsible for updating the associated entity.
     * @param id            The identifier of the associated entity to be updated.
     * @param handler       A handler function that can modify the associated entity before updating.
     * @return A reference to this LockedContext, enabling method chaining.
     * @throws RuntimeException         if an exception occurs during the update operation or if the handler function
     *                                  throws an exception. This exception typically wraps any underlying exceptions
     *                                  that may occur during the execution of the update operation or the handler function.
     *                                  It indicates that the update operation was unsuccessful.
     * @throws IllegalArgumentException if the provided relational DAO or identifier is null. This exception
     *                                  indicates invalid or missing inputs.
     *
     * <p>
     * This method allows for the updating of an associated entity using a provided relational DAO. It requires specifying
     * the identifier of the entity to be updated and, optionally, a handler function that can apply custom modifications
     * to the entity before the update operation is performed.
     * </p>
     *
     * <p>
     * The {@code handler} function, if provided, takes the associated entity (of type U) as input and can be used to
     * apply custom changes to the entity's state before it is updated using the provided {@code relationalDao}.
     * </p>
     *
     * <p>
     * If the update operation is successful, this method returns a reference to the current LockedContext, allowing for
     * method chaining. However, if an exception occurs during the update operation or if the handler function throws an
     * exception, a {@code RuntimeException} is thrown, indicating that the update operation was unsuccessful.
     * </p>
     */
    public <U> LockedContext<T> update(RelationalDao<U> relationalDao, Object id, Function<U, U> handler) {
        return apply(parent -> {
            try {
                relationalDao.update(this, id, handler);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    /**
     * Creates or updates an associated entity using a relational DAO, criteria, an updater function, and an entity generator.
     *
     * @param <U>           The type of the parent entity associated with LockedContext
     * @param relationalDao The relational DAO responsible for creating or updating the associated entity.
     * @param criteria      The criteria used to determine whether to create or update the entity.
     * @param updater       A function that can modify the associated entity before updating or creating.
     * @param entityGenerator A supplier function that generates a new entity if needed.
     * @return A reference to this LockedContext, enabling method chaining.
     *
     * <p>
     * This method allows for the creation or updating of an associated entity using a provided relational DAO, criteria,
     * an updater function, and an entity generator. The criteria are used to determine whether to create a new entity or
     * update an existing one.
     * </p>
     *
     * <p>
     * The {@code relationalDao} parameter represents an instance of a {@link RelationalDao} responsible for the
     * create or update operation. The {@code criteria} parameter defines the criteria for determining whether to
     * create or update the entity. The {@code updater} function can modify the associated entity before the create or
     * update operation is performed, and the {@code entityGenerator} is used to supply a new entity if creation is required.
     * </p>
     *
     * <p>
     * After the create or update operation is applied, this method returns a reference to the current LockedContext,
     * enabling method chaining or further operations on the context.
     * </p>
     *
     * <p>
     * If an exception occurs during the create or update operation, it is wrapped in a {@link RuntimeException},
     * indicating that the operation was unsuccessful.
     * </p>
     */
    public <U> LockedContext<T> createOrUpdate(
            RelationalDao<U> relationalDao,
            DetachedCriteria criteria,
            Function<U, U> updater,
            Supplier<U> entityGenerator) {
        return apply(parent -> {
            try {
                relationalDao.createOrUpdate(this, criteria, updater, entityGenerator);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    /**
     * Creates or updates using this context using the provided relational data access object (DAO),
     * query specification, updater function, and entity generator
     *
     * @param <U>             The type of entity being operated upon by the DAO, updater, and generator.
     * @param relationalDao   The relational data access object responsible for performing the create
     *                        or update operation on the entity.
     * @param querySpec       The query specification that defines the criteria for locating the entity
     *                        to be created or updated.
     * @param updater         A function that specifies how to update the entity if it already exists.
     *                        It takes an existing entity as input and returns the updated entity.
     * @param entityGenerator A supplier function that provides a new entity to be created if the entity
     *                        specified by the query specification does not exist.
     * @return A LockedContext<T> representing the result of the create or update operation.
     * @throws RuntimeException If an exception occurs during the create or update operation, it is wrapped
     *                          in a RuntimeException and thrown.
     */
    public <U> LockedContext<T> createOrUpdate(
            RelationalDao<U> relationalDao,
            QuerySpec<U, U> querySpec,
            Function<U, U> updater,
            Supplier<U> entityGenerator) {
        return apply(parent -> {
            try {
                relationalDao.createOrUpdate(this, querySpec, updater, entityGenerator);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    /**
     * Updates entities in the context using the provided relational data access object (DAO),
     * criteria for selecting entities, an updater function, and a boolean supplier for
     * determining whether to continue updating the next matching entity.
     *
     * @param <U>           The type of entity being operated upon by the DAO and updater.
     * @param relationalDao The relational data access object responsible for performing the update
     *                      operation on the selected entities.
     * @param criteria      The criteria that define which entities should be updated. This can be a
     *                      specification of the conditions that entities must meet to be considered
     *                      for updating.
     * @param updater       A function that specifies how to update an entity. It takes an existing
     *                      entity as input and returns the updated entity.
     * @param updateNext    A boolean supplier that determines whether to continue updating the next
     *                      matching entity. If this supplier returns true, the update operation
     *                      continues to the next matching entity; if it returns false, the operation
     *                      stops.
     * @return A LockedContext<T> representing the result of the update operation
     * @throws RuntimeException If an exception occurs during the update operation, it is wrapped
     *                          in a RuntimeException and thrown.
     */
    public <U> LockedContext<T> update(
            RelationalDao<U> relationalDao,
            DetachedCriteria criteria,
            Function<U, U> updater,
            BooleanSupplier updateNext) {
        return apply(parent -> {
            try {
                relationalDao.update(this, criteria, updater, updateNext);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    /**
     * Updates entities in the context using the provided relational data access object (DAO),
     * query specification for selecting entities, an updater function, and a boolean supplier
     * for determining whether to continue updating the next matching entity.
     *
     * @param <U>           The type of entity being operated upon by the DAO and updater.
     * @param relationalDao The relational data access object responsible for performing the update
     *                      operation on the selected entities.
     * @param criteria      The query specification that defines which entities should be updated.
     *                      This specification typically includes conditions and filters to select
     *                      the entities to be updated.
     * @param updater       A function that specifies how to update an entity. It takes an existing
     *                      entity as input and returns the updated entity.
     * @param updateNext    A boolean supplier that determines whether to continue updating the next
     *                      matching entity. If this supplier returns true, the update operation
     *                      continues to the next matching entity; if it returns false, the operation
     *                      stops.
     * @return A LockedContext<T> representing the result of the update operation.
     * @throws RuntimeException If an exception occurs during the update operation, it is wrapped
     *                          in a RuntimeException and thrown.
     */
    public <U> LockedContext<T> update(
            RelationalDao<U> relationalDao,
            QuerySpec<U, U> criteria,
            Function<U, U> updater,
            BooleanSupplier updateNext) {
        return apply(parent -> {
            try {
                relationalDao.update(this, criteria, updater, updateNext);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    /**
     * Filters the current context based on a predicate, throwing an IllegalArgumentException on failure.
     *
     * This overloaded version of the filter method allows you to apply a predicate to the current context's parent entity.
     * If the predicate evaluates to `false`, it throws an IllegalArgumentException with a default message. If the predicate
     * evaluates to `true`, the context remains unchanged.
     *
     * @param predicate The predicate used to filter the context's parent entity.
     * @return A LockedContext representing the filtered or unchanged context.
     * @throws IllegalArgumentException If the predicate evaluates to `false`, an IllegalArgumentException is thrown
     *                                  with a default message.
     */
    public LockedContext<T> filter(Predicate<T> predicate) {
        return filter(predicate, new IllegalArgumentException("Predicate check failed"));
    }

    /**
     * Filters the current context based on a predicate, throwing an exception on failure.
     *
     * This method allows you to apply a predicate to the current context's parent entity. If the predicate
     * evaluates to `false`, it throws the specified runtime exception. If the predicate evaluates to `true`,
     * the context remains unchanged.
     *
     * @param predicate The predicate used to filter the context's parent entity.
     * @param failureException The runtime exception to throw if the predicate evaluates to `false`.
     * @return A LockedContext representing the filtered or unchanged context.
     * @throws RuntimeException If the predicate evaluates to `false`, the specified runtime exception is thrown.
     */
    public LockedContext<T> filter(Predicate<T> predicate, RuntimeException failureException) {
        return apply(parent -> {
            boolean result = predicate.test(parent);
            if (!result) {
                throw failureException;
            }
            return null;
        });
    }


    /**
     * Executes a series of operations within a transactional context and returns a result.
     *
     * This method executes a series of operations within a transactional context. It uses an
     * observer to manage the execution and provides transactional handling for the operations. The operations
     * are applied to generate an entity result, and the result is returned.
     *
     * @return The result of executing the series of operations within a transactional context.
     * @throws RuntimeException If an exception occurs during the execution of the operations or transaction handling.
     */
    public T execute() {
        return observer.execute(executionContext, () -> {
            TransactionHandler transactionHandler = new TransactionHandler(sessionFactory, false);
            transactionHandler.beforeStart();
            try {
                T result = generateEntity();
                operations
                        .forEach(operation -> operation.apply(result));
                return result;
            } catch (Exception e) {
                transactionHandler.onError();
                throw e;
            } finally {
                transactionHandler.afterEnd();
            }
        });
    }

    private TransactionExecutionContext buildExecutionContext(final ShardInfoProvider shardInfoProvider,
                                                              final Class<T> entityClass) {
        return TransactionExecutionContext.builder()
                .shardName(shardInfoProvider.shardName(shardId))
                .lockedContextMode(mode.name())
                .entityClass(entityClass)
                .daoClass(getClass())
                .opType("execute")
                .build();
    }

    private T generateEntity() {
        T result = null;
        switch (mode) {
            case READ:
                result = getter.get();
                if (result == null) {
                    throw new RuntimeException("Entity doesn't exist");
                }
                break;
            case INSERT:
                result = saver.apply(entity);
                break;
            default:
                break;

        }
        return result;
    }
}
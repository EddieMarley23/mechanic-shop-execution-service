package org.project.mechanic_shop.execution.shared.config.system;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;

@Configuration
public class MongoConfig {

	/**
	 * Enables {@code @Transactional} over MongoDB, which the outbox depends on: the work order
	 * change and its Saga event must be committed together. Requires a replica set (MongoDB Atlas
	 * is always one; the local container is started as a single-node replica set).
	 */
	@Bean
	public MongoTransactionManager transactionManager(MongoDatabaseFactory databaseFactory) {
		return new MongoTransactionManager(databaseFactory);
	}
}

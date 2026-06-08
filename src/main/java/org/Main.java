package org;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisStringCommands;
import lombok.extern.slf4j.Slf4j;
import org.dao.CountryDAO;
import org.domain.*;
import org.flywaydb.core.Flyway;
import org.hibernate.*;
import org.redis.*;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.Environment;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Main application class for loading world database data into Redis cache.
 * 
 * This class orchestrates:
 * 1. Database migrations using Flyway
 * 2. Fetching country, city, and language data from MySQL via Hibernate
 * 3. Transforming relational data into flat DTO objects
 * 4. Caching the transformed data in Redis for fast access
 */
@Slf4j
public class Main {

    private final SessionFactory sessionFactory;
    private final CountryDAO countryDAO;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RedisClient redisClient;

    /**
     * Constructor to initialize the application with database and cache clients.
     * 
     * @param sessionFactory Hibernate SessionFactory for database operations
     * @param countryDAO DAO for accessing country data
     * @param redisClient Redis client for cache operations
     */
    public Main(SessionFactory sessionFactory, CountryDAO countryDAO, RedisClient redisClient) {
        this.sessionFactory = sessionFactory;
        this.countryDAO = countryDAO;
        this.redisClient = redisClient;
    }

    /**
     * Fetches all city-country relationships from the database.
     * 
     * Creates a Hibernate session, retrieves all countries via DAO,
     * and transforms them into flat CityCountry DTOs containing denormalized data.
     * 
     * @return List of CityCountry objects with city, country, and language information
     */
    private List<CityCountry> fetchAllCityCountries() {
        try (Session session = sessionFactory.getCurrentSession()) {
            session.beginTransaction();
            List<Country> countries = countryDAO.getAll();
            session.getTransaction().commit();
            return transformData(countries);
        }
    }

    /**
     * Transforms relational domain objects into flat DTO objects.
     * 
     * Denormalizes Country -> City + Language relationships into individual
     * CityCountry objects where each object contains all related information.
     * This flattening is useful for caching in Redis.
     * 
     * @param countries List of Country entities with nested cities and languages
     * @return Flat list of CityCountry DTOs
     */
    private List<CityCountry> transformData(List<Country> countries) {
        return countries.stream()
                // For each country, flatten its cities and languages
                .flatMap(country -> {
                    // Extract and transform languages for this country
                    List<Language> languageDtos = country.getLanguages().stream().map(cl -> {
                        Language dto = new Language();
                        dto.setName(cl.getLanguage());
                        dto.setOfficial(Boolean.TRUE.equals(cl.getOfficial()));
                        dto.setPercentage(cl.getPercentage());
                        return dto;
                    }).collect(Collectors.toList());

                    // Create a CityCountry DTO for each city in this country
                    return country.getCities().stream().map(city -> {
                        CityCountry dto = new CityCountry();
                        // Set city-specific fields
                        dto.setCityId(city.getId());
                        dto.setCityName(city.getName());
                        dto.setDistrict(city.getDistrict());
                        dto.setPopulation(city.getPopulation());

                        // Set country-specific fields
                        dto.setCountryId(country.getId());
                        dto.setCountryName(country.getName());
                        dto.setRegion(country.getRegion());
                        dto.setContinent(country.getContinent());

                        // Associate languages with this city-country combination
                        dto.setLanguages(languageDtos);
                        return dto;
                    });
                })
                .collect(Collectors.toList());
    }

    /**
     * Initializes and tests the Redis connection.
     * 
     * Creates a RedisClient instance and verifies connectivity to the Redis server
     * running on localhost:6379.
     * 
     * @return RedisClient instance connected to the Redis server
     */
    private RedisClient preparedRedisClient() {
        RedisClient client = RedisClient.create(RedisURI.create("localhost", 6379));
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            log.info("Connected to Redis");
        }
        return client;
    }

    /**
     * Pushes city-country data to Redis cache.
     * 
     * For each CityCountry object, creates a Redis key (format: "city: {cityId}")
     * and stores the serialized JSON value. This enables fast data retrieval
     * from cache instead of querying the database.
     * 
     * @param data List of CityCountry objects to cache
     */
    private void pushToRedis(List<CityCountry> data) {
        try (StatefulRedisConnection<String, String> connection = redisClient.connect()) {
            RedisStringCommands<String, String> redisCommands = connection.sync();
            for (CityCountry cityCountry : data) {
                String key = "city: " + cityCountry.getCityId();
                String value = mapper.writeValueAsString(cityCountry);
                redisCommands.set(key, value);
            }
        } catch (Exception e) {
            log.error("Redis load error " + e);
        }
    }

    /**
     * Application entry point.
     * 
     * Orchestrates the complete workflow:
     * 1. Runs database migrations via Flyway
     * 2. Sets up Hibernate SessionFactory
     * 3. Loads country/city/language data from MySQL database
     * 4. Transforms relational data into flat DTOs
     * 5. Caches data in Redis
     * 6. Logs and outputs results
     * 7. Cleans up resources
     * 
     * @param args Command line arguments (not used)
     */
    public static void main(String[] args) {
        // Run Flyway database migrations
        runMigrations();

        // Initialize Hibernate ORM and DAO layer
        SessionFactory factory = prepareRelationalDB();
        Main app = new Main(factory, new CountryDAO(factory), RedisClient.create(RedisURI.create("localhost", 6379)));
        
        // Fetch data from database and transform to flat DTOs
        List<CityCountry> cityCountries = app.fetchAllCityCountries();
        
        // Load transformed data into Redis cache
        app.pushToRedis(cityCountries);
        
        // Log results
        log.info("Countries loaded: " + cityCountries.size());
        System.out.println("Countries loaded: " + cityCountries.size());
        
        // Cleanup resources
        app.shutdown();
    }

    /**
     * Cleanly shuts down the Hibernate SessionFactory.
     * 
     * Ensures database connections are properly closed and resources are released.
     */
    private void shutdown() {
        if (sessionFactory != null && !sessionFactory.isClosed()) {
            sessionFactory.close();
        }
    }

    /**
     * Configures and initializes the Hibernate SessionFactory for MySQL database access.
     * 
     * Configuration includes:
     * - MySQL 8 dialect for SQL generation
     * - P6Spy driver for query logging/monitoring
     * - Connection pool settings and batch processing
     * - Annotated entity classes (Country, City, CountryLanguage)
     * 
     * Note: Database credentials are currently hardcoded. For production,
     * consider loading from application.properties or environment variables.
     * 
     * @return Configured SessionFactory ready for database operations
     */
    private static SessionFactory prepareRelationalDB() {
        Properties properties = new Properties();
        properties.put(Environment.DIALECT, "org.hibernate.dialect.MySQL8Dialect");
        properties.put(Environment.DRIVER, "com.p6spy.engine.spy.P6SpyDriver");
        properties.put(Environment.URL, "jdbc:p6spy:mysql://localhost:3306/world");

        // TODO: Load credentials from external configuration instead of hardcoding
//        Properties secretProperties = new Properties();
//        try (InputStream inputStream = Files.newInputStream(Paths.get("application.properties"))) {
//            secretProperties.load(inputStream);
//        } catch (IOException e) {
//            throw new RuntimeException("File not found " + e);
//        }
//
//        String dbUser = secretProperties.getProperty("db.user");
//        String dbPassword = secretProperties.getProperty("db.password");
//
//        if (dbUser == null || dbPassword == null) {
//            throw new RuntimeException("db.user or db.password not found");
//        }
//
//        properties.put(Environment.USER, dbUser);
//        properties.put(Environment.PASS, dbPassword);
        
        // Hardcoded credentials (development only)
        properties.put(Environment.USER, "root");
        properties.put(Environment.PASS, "root");
        
        // Session and batch configuration
        properties.put(Environment.CURRENT_SESSION_CONTEXT_CLASS, "thread");
        properties.put(Environment.HBM2DDL_AUTO, "none");
        properties.put(Environment.STATEMENT_BATCH_SIZE, "100");
        
        // Build and return configured SessionFactory
        return new Configuration()
                .addAnnotatedClass(Country.class)
                .addAnnotatedClass(City.class)
                .addAnnotatedClass(CountryLanguage.class)
                .addProperties(properties)
                .buildSessionFactory();
    }

    /**
     * Runs Flyway database migrations to initialize/update the schema.
     * 
     * Configures Flyway to:
     * - Connect to the world database on localhost
     * - Use baseline versioning for initial setup
     * - Automatically create a baseline if migrating existing data
     * - Execute all pending migration scripts
     * 
     * Note: Database credentials are currently hardcoded. Consider
     * extracting to configuration properties for production use.
     */
    private static void runMigrations() {
        String dbUser = "root";
        String dbPassword = "root";

        Flyway flyway = Flyway.configure()
                .dataSource("jdbc:mysql://localhost:3306/world", dbUser, dbPassword)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
        flyway.migrate();
    }

}

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

@Slf4j
public class Main {

    private final SessionFactory sessionFactory;
    private final CountryDAO countryDAO;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RedisClient redisClient;

    public Main(SessionFactory sessionFactory, CountryDAO countryDAO, RedisClient redisClient) {
        this.sessionFactory = sessionFactory;
        this.countryDAO = countryDAO;
        this.redisClient = redisClient;
    }

    private List<CityCountry> fetchAllCityCountries() {
        try (Session session = sessionFactory.getCurrentSession()) {
            session.beginTransaction();
            List<Country> countries = countryDAO.getAll();
            session.getTransaction().commit();
            return transformData(countries);
        }
    }

    private List<CityCountry> transformData(List<Country> countries) {
        return countries.stream()
                .flatMap(country -> {
                    List<Language> languageDtos = country.getLanguages().stream().map(cl -> {
                        Language dto = new Language();
                        dto.setName(cl.getLanguage());
                        dto.setOfficial(Boolean.TRUE.equals(cl.getOfficial()));
                        dto.setPercentage(cl.getPercentage());
                        return dto;
                    }).collect(Collectors.toList());

                    return country.getCities().stream().map(city -> {
                        CityCountry dto = new CityCountry();
                        dto.setCityId(city.getId());
                        dto.setCityName(city.getName());
                        dto.setDistrict(city.getDistrict());
                        dto.setPopulation(city.getPopulation());

                        dto.setCountryId(country.getId());
                        dto.setCountryName(country.getName());
                        dto.setRegion(country.getRegion());
                        dto.setContinent(country.getContinent());

                        dto.setLanguages(languageDtos);
                        return dto;
                    });
                })
                .collect(Collectors.toList());
    }

    private RedisClient preparedRedisClient() {
        RedisClient client = RedisClient.create(RedisURI.create("localhost", 6379));
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            log.info("Connected to Redis");
        }
        return client;
    }

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

    public static void main(String[] args) {
        //Запуск Flyway
        runMigrations();

        SessionFactory factory = prepareRelationalDB();
        Main app = new Main(factory, new CountryDAO(factory), RedisClient.create(RedisURI.create("localhost", 6379)));
        List<CityCountry> cityCountries = app.fetchAllCityCountries();
        app.pushToRedis(cityCountries);
        log.info("Countries loaded: " + cityCountries.size());
        System.out.println("Countries loaded: " + cityCountries.size());
        app.shutdown();
    }

    private void shutdown() {
        if (sessionFactory != null && !sessionFactory.isClosed()) {
            sessionFactory.close();
        }
    }

    private static SessionFactory prepareRelationalDB() {
        Properties properties = new Properties();
        properties.put(Environment.DIALECT, "org.hibernate.dialect.MySQL8Dialect");
        properties.put(Environment.DRIVER, "com.p6spy.engine.spy.P6SpyDriver");
        properties.put(Environment.URL, "jdbc:p6spy:mysql://localhost:3306/world");

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
        properties.put(Environment.USER, "root");
        properties.put(Environment.PASS, "root");
        properties.put(Environment.CURRENT_SESSION_CONTEXT_CLASS, "thread");
        properties.put(Environment.HBM2DDL_AUTO, "none");
        properties.put(Environment.STATEMENT_BATCH_SIZE, "100");
        return new Configuration()
                .addAnnotatedClass(Country.class)
                .addAnnotatedClass(City.class)
                .addAnnotatedClass(CountryLanguage.class)
                .addProperties(properties)
                .buildSessionFactory();
    }

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


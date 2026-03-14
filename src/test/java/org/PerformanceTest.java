package org;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.*;
import io.lettuce.core.api.*;
import io.lettuce.core.api.sync.RedisStringCommands;
import lombok.extern.slf4j.Slf4j;
import org.dao.*;
import org.domain.*;
import org.hibernate.*;
import org.hibernate.cfg.*;
import org.junit.jupiter.api.*;
import org.redis.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import static junit.framework.Assert.assertNotNull;

@Slf4j
class PerformanceTest {

    private static SessionFactory sessionFactory;
    private static CountryDAO countryDAO;
    private static CityDAO cityDAO;
    private static RedisClient redisClient;
    private static ObjectMapper mapper;
    private static List<Integer> testIds = List.of(1, 20, 45, 100, 250, 300, 400, 500, 600, 700);

    @BeforeAll
    static void setup() {
        sessionFactory = prepareRelationalDB();
        countryDAO = new CountryDAO(sessionFactory);
        cityDAO = new CityDAO(sessionFactory);

        redisClient = RedisClient.create(RedisURI.create("localhost", 6379));
        try (StatefulRedisConnection<String, String> connection = redisClient.connect()) {
            log.info("Connection with Redis established");
            System.out.println("Connection with Redis established");
        }

        mapper = new ObjectMapper();

        List<Country> countries;
        try (Session session = sessionFactory.getCurrentSession()) {
            session.beginTransaction();
            countries = countryDAO.getAll();
            session.getTransaction().commit();
        }
        List<CityCountry> cityCountries = transformData(countries);
        pushToRedis(cityCountries);
    }

    @AfterAll
    static void tearDown() {
        if (sessionFactory != null && !sessionFactory.isClosed()) {
            sessionFactory.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @Test
    void testRedisPerformance() {
        long start = System.currentTimeMillis();
        try (StatefulRedisConnection<String, String> connection = redisClient.connect()) {
            RedisStringCommands<String, String> redisStringCommands = connection.sync();
            for (Integer id : testIds) {
                String json = redisStringCommands.get("city: " + id);
                assertNotNull(json, "City data " + id + " not found in Redis.");
                mapper.readValue(json, CityCountry.class);
            }
        } catch (JsonProcessingException e) {
            log.error("Error " + e);
        }
        long duration = System.currentTimeMillis() - start;
        log.info("Redis reading of 10 cities " + duration + " ms");
        System.out.println("Redis reading of 10 cities " + duration + " ms");
    }

    @Test
    void testMySqlPerformance() {
        long start = System.currentTimeMillis();
        try (Session session = sessionFactory.getCurrentSession()) {
            session.beginTransaction();
            for (Integer id : testIds) {
                City city = cityDAO.getById(id);
                assertNotNull(String.valueOf(city), "City with id " + id + " not found.");
                city.getCountry().getLanguages().size();
            }
            session.getTransaction().commit();
        }
        long duration = System.currentTimeMillis() - start;
        log.info("MySQL reading of 10 cities " + duration + " ms");
        System.out.println("MySQL reading of 10 cities " + duration + " ms");
    }

    private static void pushToRedis(List<CityCountry> data) {
        try (StatefulRedisConnection<String, String> connection = redisClient.connect()) {
            RedisStringCommands<String, String> redisCommands = connection.sync();
            for (CityCountry dto : data) {
                String key = "city: " + dto.getCityId();
                String value = mapper.writeValueAsString(dto);
                redisCommands.set(key, value);
            }
        } catch (JsonProcessingException e) {
            log.error("Redis load error " + e);
        }
    }

    private static List<CityCountry> transformData(List<Country> countries) {
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

    private static SessionFactory prepareRelationalDB() {
        Properties properties = new Properties();
        properties.put(Environment.DIALECT, "org.hibernate.dialect.MySQL8Dialect");
        properties.put(Environment.DRIVER, "com.p6spy.engine.spy.P6SpyDriver");
        properties.put(Environment.URL, "jdbc:p6spy:mysql://localhost:3306/world");
//        String dbUser = System.getenv("db.user");
//        String dbPassword = System.getenv("db.password");

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
}


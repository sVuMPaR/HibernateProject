package org;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisStringCommands;
import org.redis.CityCountry;
import org.dao.CityDAO;
import org.dao.CountryDAO;
import org.domain.City;
import org.domain.Country;
import org.domain.CountryLanguage;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.Environment;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.redis.Language;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 5)
@Measurement(iterations = 3, time = 5)
@Fork(1)
public class BenchmarkTest {
    private SessionFactory sessionFactory;
    private CountryDAO countryDAO;
    private CityDAO cityDAO;
    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> redisConnection;
    private RedisStringCommands<String, String> redisCommands;
    private ObjectMapper objectMapper;
    private List<Integer> testIds = List.of(1, 20, 45, 100, 250, 300, 400, 500, 600, 700);

    public static void main(String[] args) {
        Options options = new OptionsBuilder()
                .include(BenchmarkTest.class.getSimpleName())
                .build();
        try {
            new Runner(options).run();
        } catch (RunnerException e) {
            throw new RuntimeException(e);
        }
    }

    @Setup(Level.Trial)
    public void setup() {
        sessionFactory = prepareRelationalDB();
        countryDAO = new CountryDAO(sessionFactory);
        cityDAO = new CityDAO(sessionFactory);
        redisClient = RedisClient.create(RedisURI.create("localhost", 6379));
        objectMapper = new ObjectMapper();
        redisConnection = redisClient.connect();
        redisCommands = redisConnection.sync();

        List<Country> countries;
        try (Session session = sessionFactory.getCurrentSession()) {
            session.beginTransaction();
            countries = countryDAO.getAll();
            session.getTransaction().commit();
        }

        List<CityCountry> cityCountries = transformData(countries);
        try {
            for (CityCountry dto : cityCountries) {
                redisCommands.set("city: " + dto.getCityId(), objectMapper.writeValueAsString(dto));
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (sessionFactory != null && !sessionFactory.isClosed()) {
            sessionFactory.close();
        }
        if (redisConnection != null && redisConnection.isOpen()) {
            redisConnection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @Benchmark
    public void readFromRedis() {
        for (Integer id : testIds) {
            String json = redisCommands.get("city: " + id);
            if (json != null) {
                try {
                    objectMapper.readValue(json, CityCountry.class);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    @Benchmark
    public void readFromMySQL() {
        try (Session session = sessionFactory.getCurrentSession()) {
            session.beginTransaction();
            for (Integer id : testIds) {
                City city = cityDAO.getById(id);
                if (city != null && city.getCountry() != null) {
                    city.getCountry().getLanguages().size();
                }
            }
            session.getTransaction().commit();
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

//        Properties secretProperties = new Properties();
//        try (InputStream inputStream = BenchmarkTest.class.getClassLoader().getResourceAsStream("application.properties")) {
//            if (inputStream == null) {
//                throw new RuntimeException("application.properties not found on classpath");
//            }
//            secretProperties.load(inputStream);
//        } catch (IOException e) {
//            throw new RuntimeException("Failed to load application.properties", e);
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
}

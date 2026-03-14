## ProjectFour – Hibernate + Redis benchmark on MySQL *world* database

Этот проект демонстрирует:
- доступ к базе данных **MySQL** (**`world`**) через **Hibernate ORM**
- использование **Redis** как кеша, оптимизированного для чтения агрегированных данных по городам и странам
- миграции схемы с помощью **Flyway**
- сравнение скорости чтения **MySQL vs Redis** с помощью бенчмарков **JMH** / **JUnit**

### 1. Требования

- Java **17+** (совместимая с настройками IDE)
- Maven
- Запущенный экземпляр **MySQL** с базой **`world`**:
  - host: `localhost`
  - port: `3306`
  - database: `world`
  - пользователь/пароль по умолчанию: `root` / `root` (можно изменить)
- Запущенный **Redis**:
  - host: `localhost`
  - port: `6379`

### 2. Конфигурация

- Данные для подключения к базе хранятся в `src/main/resources/application.properties`(этот способ закомментирвоан и используется подключение на прямую, файл добавлен в .gitignore):

```properties
db.user=root
db.password=root
```


- Подключение **Hibernate** настраивается программно в `Main.prepareRelationalDB()`:
  - driver: `com.p6spy.engine.spy.P6SpyDriver`
  - URL: `jdbc:p6spy:mysql://localhost:3306/world`

- Миграции **Flyway** выполняются при старте приложения в методе `Main.runMigrations()` для URL:

```text
jdbc:mysql://localhost:3306/world
```

### 3. Сборка проекта

Из корневой директории проекта (`ProjectFour`):

```bash
mvn clean install
```

Команда скачает зависимости, соберёт проект и запустит тесты.

### 4. Запуск основного приложения

Точка входа — класс `Main`:
- загружает данные из **MySQL** через `CountryDAO` / **Hibernate**
- преобразует их в DTO `CityCountry`
- записывает данные в **Redis** в формате JSON

Запуск из IDE:
- запусти класс `Main` как обычное Java-приложение.

Перед запуском обязательно убедись, что **MySQL** и **Redis** запущены и доступны.

### 5. Бенчмарки

Поддерживаются два способа измерения производительности:

- **JUnit-подобный тест производительности** — `org.City.PerformanceTest`
  - простое сравнение чтения 10 городов из **MySQL** и **Redis**.

- **JMH microbenchmark** — `org.City.BenchmarkTest`:
  - использует **JMH** для измерения среднего времени выполнения:
    - `readFromMySQL` — читает города по id через **Hibernate**
    - `readFromRedis` — читает заранее загруженные `CityCountry` из **Redis** и десериализует JSON
  - запускается через метод `main` этого класса или отдельную конфигурацию **JMH** в IDE.

### 6. Логирование

Логирование настроено через **SLF4J + Logback**.  
Ограничить вывод в консоль можно, добавив или изменив `logback.xml` в `src/main/resources`, например:

```xml
<root level="WARN">
    <appender-ref ref="CONSOLE"/>
</root>
```

Такой уровень подавит большинство сообщений уровня INFO/DEBUG от **Hibernate** и других библиотек.

### 7. Примечания

- Сущности описаны в пакете `org.domain` (`Country`, `City`, `CountryLanguage`).
- **Lombok** используется для генерации getters/setters; `equals`/`hashCode` основаны только на первичных ключах, чтобы избежать проблем с коллекциями в **Hibernate**.
- **P6Spy** используется как JDBC-драйвер для логирования SQL-запросов, выполняемых через **Hibernate**.

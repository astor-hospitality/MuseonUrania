# Образ портала.
#
# Собирается из исходников внутри образа, а не из готового jar с машины
# разработчика: собранный локально jar зависит от того, что установлено
# на этой машине, и «у меня работает» перестаёт быть шуткой ровно в тот
# момент, когда образ уезжает на сервер.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /build

# Сначала обёртка и все pom'ы, потом исходники. Слой с зависимостями
# переиспользуется, пока не поменялся ни один pom, — правка одного класса
# не тянет за собой повторную выкачку всего Maven Central.
#
# Модулей двенадцать, и каждый копируется отдельной строкой намеренно:
# `COPY . .` утащил бы сюда и исходники, и весь смысл раздельных слоёв
# пропал бы. Новый модуль — новая строка здесь; забыть её нельзя, сборка
# упадёт на разборе реактора.
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY common/pom.xml         common/
COPY iam/pom.xml            iam/
COPY audit/pom.xml          audit/
COPY catalog/pom.xml        catalog/
COPY content/pom.xml        content/
COPY documents/pom.xml      documents/
COPY crm/pom.xml            crm/
COPY gateway/pom.xml        gateway/
COPY notifications/pom.xml  notifications/
COPY assistant/pom.xml      assistant/
COPY chat/pom.xml           chat/
COPY admin/pom.xml          admin/
COPY app/pom.xml            app/

# Перевод строк выправляется здесь, а не только .gitattributes: Docker копирует
# рабочее дерево, а не индекс. На Windows файл с CRLF уносит \r в строку
# shebang, и сборка падает с './mvnw: not found' — сообщением, по которому
# причину видно последней.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw

# Выкачка зависимостей отдельным слоем: пока pom'ы не менялись, правка
# класса переиспользует его целиком.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline || true

COPY common/src         common/src
COPY iam/src            iam/src
COPY audit/src          audit/src
COPY catalog/src        catalog/src
COPY content/src        content/src
COPY documents/src      documents/src
COPY crm/src            crm/src
COPY gateway/src        gateway/src
COPY notifications/src  notifications/src
COPY assistant/src      assistant/src
COPY chat/src           chat/src
COPY admin/src          admin/src
COPY app/src            app/src

# Кэш Maven живёт между сборками образа, а не внутри слоя: класть ~/.m2
# в слой значит носить сотню мегабайт зависимостей в каждом образе.
#
# Тесты здесь не идут намеренно: они поднимают PostgreSQL через
# Testcontainers, то есть требуют Docker внутри сборки Docker. Их место —
# в CI перед сборкой образа, и образ собирается только из зелёного дерева.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -DskipTests package

FROM eclipse-temurin:25-jre AS runtime

# curl нужен healthcheck'у в compose. Пакет ставится в runtime, а не
# тащится из сборочного образа: там он был бы вместе с JDK и Maven.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Не root. Приложение пишет только во временный каталог и (в режиме local)
# в каталог хранилища — права root ему не нужны ни для чего.
RUN useradd --system --create-home --uid 10001 vedal
USER vedal
WORKDIR /app

# Приложение собирает единственный модуль реактора — app. Имя без версии
# задано в его pom (finalName), поэтому поднятие версии не требует правки
# этой строки.
COPY --from=build --chown=vedal:vedal /build/app/target/portal.jar app.jar

# Корневые сертификаты, которых нет в хранилище JVM: «Russian Trusted
# Root CA» Минцифры, которым подписаны двери GigaChat. Каталог едет в образ
# целиком (с README, если сертификата ещё нет), а подключается явно —
# GIGACHAT_CA_BUNDLE=/app/certs/russian_trusted_root_ca.pem: корень
# добавляется к штатным, а не заменяет их, и проверка TLS не выключается.
# Пока провайдер — Яндекс, каталог никому не мешает.
COPY --chown=vedal:vedal certs/ /app/certs/

# Каталог для режима vedal.storage.kind=local. В стеке compose хранилище
# объектное, и этот каталог остаётся пустым — но образ не должен падать
# только потому, что кто-то запустил его без объектного хранилища.
RUN mkdir -p /app/var/documents
VOLUME ["/app/var/documents"]

EXPOSE 8081

# Контейнер получает сигнал остановки напрямую, без оболочки-посредника:
# иначе Spring не успевает закрыть соединения и долить outbox, и docker
# добивает процесс по таймауту.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

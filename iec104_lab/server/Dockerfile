FROM eclipse-temurin:17-jdk

WORKDIR /app

# Copy j60870 from host into container build context
COPY j60870/ /app/j60870

# Build j60870
WORKDIR /app/j60870
RUN ./gradlew --no-daemon -x asciidoctor -x test
RUN ./gradlew jar

# Default command
CMD java -cp java -cp build/libs/j60870-1.7.2.jar:cli-app/build/classes/java/main org.openmuc.j60870.app.SampleServer -a 172.30.0.2

FROM openjdk:17

WORKDIR /app
COPY . .

RUN javac backend/ProteinAPI.java

CMD ["java", "-cp", "backend", "ProteinAPI"]
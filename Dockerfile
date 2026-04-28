FROM openjdk:17

WORKDIR /app

COPY . .

RUN javac backend/ProteinAPI.java

EXPOSE 8080

CMD ["sh", "-c", "java -cp backend ProteinAPI"]
FROM gcr.io/distroless/java21-debian12:nonroot
WORKDIR /
COPY ./target/payment-service-0.0.1-SNAPSHOT.jar /target.jar
CMD ["/target.jar"]
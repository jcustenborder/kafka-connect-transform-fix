# Introduction
[Documentation](https://jcustenborder.github.io/kafka-connect-documentation/projects/kafka-connect-transform-fix)

Installation through the [Confluent Hub Client](https://docs.confluent.io/current/connect/managing/confluent-hub/client.html)

This project provides transformations that can be utilised to process messages that are encoded with the FIX protocol.



## [FromFix](https://jcustenborder.github.io/kafka-connect-documentation/projects/kafka-connect-transform-fix/transformations/FromFIX.html)

This transformation is used to read FIX encoded messages and convert them to a representation of FIX that downstream systems can understand.

# Development

## Building the source

```bash
mvn clean package
```
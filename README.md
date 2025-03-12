# mi-connector-core

This repository contains the core components required by WSO2 Micro Integrator connectors.

## Table of Contents

- [Introduction](#introduction)
- [Features](#features)
- [Usage](#usage)
- [Building the Project](#building-the-project)
- [License](#license)

## Introduction

The `mi-connector-core` project provides the essential classes and interfaces for developing connectors for the WSO2 Micro Integrator. It includes utilities for handling connections, managing configurations, and processing requests and responses.

## Features

- Abstract classes and interfaces for connector development
- Connection pooling and management
- Utilities for handling JSON and XML payloads
- OAuth 2.0 token generation and refresh support

## Usage
To use the core components in your connector project, add the following dependency to your pom.xml:

```xml
<dependency>
    <groupId>org.wso2.integration.connector.core</groupId>
    <artifactId>mi-connector-core</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

## Building the Project

To build the project, run the following Maven command:

```sh
mvn clean install
```

## License
This project is licensed under the Apache License, Version 2.0. See the LICENSE file for more details.
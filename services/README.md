# Services

Each directory in this folder is an independent Spring Boot service and a Maven
module. Services do not share business entities, repositories, or database
tables. Their source-of-truth interaction schemas are in `../contracts/`.

Current modules:

- `identity-service/` — registration, activation, login and session management;
  requests profile and wallet creation through internal gRPC clients.
- `accounts-service/` — owns profiles and implements `CreateProfileIfAbsent`.
- `coins-service/` — owns wallets and implements `CreateWalletIfAbsent`.

Accounts and Coins always start their internal gRPC servers with mandatory mTLS.
Prepare the certificates using the [local setup instructions](../LOCAL-SETUP.md)
and configure the paths in each service's `.env`. Missing or invalid TLS
materials prevent startup. The removed `COINS_GRPC_ENABLED` setting no longer
disables the server.

Spec 011 renames the internal profile/wallet contracts within v1. Rebuild and
restart Identity, Accounts and Coins together: old binaries use different gRPC
method paths and cannot communicate with the renamed APIs.

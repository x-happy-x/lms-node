.PHONY: help test package deploy deploy-s1 deploy-s2

help:
	@echo "Available targets: test, package, deploy, deploy-s1, deploy-s2"

test:
	./mvnw test

package:
	./mvnw -DskipTests package

deploy:
	./scripts/deploy-node.sh

deploy-s1:
	DEPLOY_ENV_FILE=./scripts/deploy-node-s1.env ./scripts/deploy-node.sh

deploy-s2:
	DEPLOY_ENV_FILE=./scripts/deploy-node-s2.env ./scripts/deploy-node.sh

.PHONY: build up down logs clean

build:
	mvn clean package -pl kairo-code-server -am -DskipTests -q
	docker compose build

up: build
	docker compose up -d

down:
	docker compose down

logs:
	docker compose logs -f

clean:
	docker compose down -v
	docker rmi kairo-code-server:latest kairo-code-web:latest 2>/dev/null || true

.PHONY: run config test

config:
	@test -f server/config.json || cp server/config.example.json server/config.json

run: config
	python3 server/llm_relay_server.py

test:
	@curl -s -X POST http://127.0.0.1:8765/send \
		-H "Content-Type: application/json" \
		-d '{"text":"hello from make test"}' && echo

run-clj:
	./bin/relay-clj

context:
	./bin/repo-context.sh

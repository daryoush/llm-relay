.PHONY: run test run-clj context install

run:
	./bin/llm-relay

test:
	@curl -s -X POST http://127.0.0.1:8765/send \
		-H "Content-Type: application/json" \
		-d '{"text":"hello from make test"}' && echo

run-clj:
	./bin/relay-clj

context:
	./bin/repo-context.sh

install:
	mkdir -p $(HOME)/.local/bin
	ln -sf $(CURDIR)/bin/llm-relay $(HOME)/.local/bin/llm-relay
	ln -sf $(CURDIR)/bin/run-md $(HOME)/.local/bin/run-md
	ln -sf $(CURDIR)/bin/run-llm $(HOME)/.local/bin/run-llm

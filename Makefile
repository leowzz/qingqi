GRADLE ?= ./gradlew
.PHONY: dev build check
dev: build
build:
	$(GRADLE) :app:assembleDebug
check:
	$(GRADLE) :app:lintDebug
	python3 scripts/check_contract.py
	python3 -m unittest discover -s tests -p 'test_release.py'

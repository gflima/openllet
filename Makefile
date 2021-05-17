.PHONY: all
all:
	@echo ${MAKE} '[compile|package|install]'

.PHONY: compile package install
compile package install:
	mvn $@ -DskipTests=true

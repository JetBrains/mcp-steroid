# Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

.PHONY: dev build clean setup update-config

VERSION := $(shell cat ../VERSION 2>/dev/null || echo "0.0.0")
WHATSNEW := $(shell cat whatsnew.txt 2>/dev/null | tr '\n' ' ' | sed 's/  */ /g' | sed 's/ $$//')

# First time setup - clone the public repository
setup:
	git clone git@github.com:jonnyzzz/mcp-steroid.git mcp-steroid-public

# Update version and whatsnew in hugo.toml
update-config:
	@echo "Updating version to $(VERSION)"
	@sed -i.bak 's/version = ".*"/version = "$(VERSION)"/' hugo.toml && rm -f hugo.toml.bak
	@echo "Updating whatsnew"
	@sed -i.bak 's/whatsnew = ".*"/whatsnew = "$(WHATSNEW)"/' hugo.toml && rm -f hugo.toml.bak

# Start development server with live reload
dev: update-config
	docker compose up dev

# Build the site to the public repository
build: update-config
	docker compose run --rm build

# Clean generated files (preserves .git and CNAME)
clean:
	find mcp-steroid-public/docs -mindepth 1 ! -name '.git' ! -name 'CNAME' -delete 2>/dev/null || true

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

.PHONY: dev build clean setup update-config ensure-public-repo copy-root-files

VERSION := $(shell cat ../VERSION)
RELEASE_ZIP_URL := $(shell gh release view "v$(VERSION)" --repo jonnyzzz/mcp-steroid --json assets --jq '[.assets[] | select(.name | endswith(".zip")) | .url][0]' 2>/dev/null || gh release view "$(VERSION)" --repo jonnyzzz/mcp-steroid --json assets --jq '[.assets[] | select(.name | endswith(".zip")) | .url][0]' 2>/dev/null)

# First time setup - clone the public repository
setup:
	git clone git@github.com:jonnyzzz/mcp-steroid.git mcp-steroid-public

# Ensure the output directory exists (auto-setup)
ensure-public-repo:
	@mkdir -p mcp-steroid-public

# Copy EULA and LICENSE from repository root into static/ so the website serves both
copy-root-files:
	cp ../EULA static/EULA
	cp ../LICENSE static/LICENSE

# Update version in hugo.toml, generate version.json
update-config: ensure-public-repo copy-root-files
	@echo "Updating version to $(VERSION)"
	@sed -i.bak 's/version = ".*"/version = "$(VERSION)"/' hugo.toml && rm -f hugo.toml.bak
	@echo "Generating version.json"
	@echo '{"version-base": "$(VERSION)"}' > static/version.json
	@if [ -z "$(RELEASE_ZIP_URL)" ]; then echo "ERROR: Could not find release ZIP for version $(VERSION). Is 'gh' installed and the release published?" >&2; exit 1; fi
	@echo "Generating updatePlugins.xml (url: $(RELEASE_ZIP_URL))"
	@./scripts/generate-update-plugins-xml.sh "$(VERSION)" "$(RELEASE_ZIP_URL)" > static/updatePlugins.xml
	@xmllint --noout static/updatePlugins.xml 2>/dev/null && echo "updatePlugins.xml is valid XML" || echo "WARNING: xmllint not available, skipping XML validation"
	@mkdir -p mcp-steroid-public/mcp-steroid-plugin

# Start development server with live reload
dev: update-config
	docker compose up dev

# Build the site to mcp-steroid-public/mcp-steroid-plugin
build: update-config
	@# Remove symlinks in output dir — they escape Docker mount boundary and break Hugo stat
	@find mcp-steroid-public/mcp-steroid-plugin -type l -delete 2>/dev/null || true
	docker compose run --rm build

# Clean generated files (recreated by build)
clean:
	find mcp-steroid-public/mcp-steroid-plugin -mindepth 1 -delete 2>/dev/null || true

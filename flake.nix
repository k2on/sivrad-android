{
  description = "Sivrad: an offline voice assistant for Android (GrapheneOS / Pixel 8)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";
    flake-parts = {
      url = "github:hercules-ci/flake-parts";
      inputs.nixpkgs-lib.follows = "nixpkgs";
    };
    android = {
      url = "github:k2on/android.nix";
      inputs.nixpkgs.follows = "nixpkgs";
      inputs.flake-parts.follows = "flake-parts";
    };
    # The same llama.cpp commit as the git submodule at
    # core/llm/src/main/cpp/llama.cpp (CI checks that they agree). Nix takes
    # it from here rather than from the submodule: a flake's own source does
    # not include submodules unless asked, and asking fails on the shallow
    # clones CI makes.
    llama-cpp = {
      url = "github:ggml-org/llama.cpp/b11160";
      flake = false;
    };
  };

  outputs = inputs@{ flake-parts, ... }:
    flake-parts.lib.mkFlake { inherit inputs; } {
      systems = [ "x86_64-linux" "aarch64-linux" ];

      imports = [ inputs.android.flakeModules.default ];

      perSystem = { pkgs, lib, android, ... }:
        let
          # Platform 36, build-tools 35/36, NDK 27.1.12297006 and CMake
          # 3.22.1, all from nixpkgs: the versions the Gradle files name, so
          # AGP never downloads a component (or a CMake/ninja) of its own.
          sdk = android.mkSdk { };

          # The version in gradle/wrapper/gradle-wrapper.properties.
          gradle = android.mkGradle {
            version = "8.14.3";
            hash = "sha256-vXEQIhNJMGCVbsIp2Ua+7lcVjb2J0OYrkbyg+ixfNTE=";
          };

          # Only what the llama.cpp CMake build reads. The repository also
          # carries ~150 MB of docs, test models and tools.
          llamaCpp = pkgs.runCommand "llama.cpp-b11160-src" { } ''
            mkdir $out
            cd ${inputs.llama-cpp}
            cp -r CMakeLists.txt LICENSE licenses cmake common ggml include src vendor $out/
          '';

          # Puts llama.cpp where the submodule would be. Shared by the state
          # layer and the build, so both see the same tree with the same
          # (store-normalised) timestamps.
          prepare = ''
            rm -rf core/llm/src/main/cpp/llama.cpp
            cp -r --preserve=timestamps ${llamaCpp} core/llm/src/main/cpp/llama.cpp
            chmod -R u+w core/llm/src/main/cpp/llama.cpp
          '';

          gradleProject = lib.fileset.unions [
            ./settings.gradle.kts
            ./build.gradle.kts
            ./gradle.properties
            ./gradle/libs.versions.toml
          ];

          # A flake's source is the git tree, so build outputs and the
          # (submodule) llama.cpp checkout are not in it.
          modules = lib.fileset.unions [ ./app ./core ];

          # Both trees unpack as "source", so the state layer's paths match
          # the build's (see android.nix's README, "The paths must match").
          src = lib.fileset.toSource {
            root = ./.;
            fileset = lib.fileset.unions [
              gradleProject
              modules
            ];
          };

          # The state layer is built from everything except Kotlin sources and
          # tests, so editing app code never invalidates the cached llama.cpp
          # compile (.cxx) or the Gradle caches. Only a change to build files,
          # resources, manifests, native code or dependencies rebuilds it.
          stateSrc = lib.fileset.toSource {
            root = ./.;
            fileset = lib.fileset.unions [
              gradleProject
              (lib.fileset.difference modules
                (lib.fileset.unions [
                  (lib.fileset.fileFilter (f: f.hasExt "kt") ./app)
                  (lib.fileset.fileFilter (f: f.hasExt "kt") ./core)
                ]))
            ];
          };

          state = android.mkGradleState {
            name = "sivrad-gradle-state";
            src = stateSrc;
            inherit sdk gradle prepare;
            gradleDeps = ./gradle-deps.json;
            mitmCache = apk.mitmCache;
          };

          apk = android.mkGradleBuild {
            name = "sivrad-debug-apk";
            inherit src sdk gradle prepare;
            gradleDeps = ./gradle-deps.json;
            # Also records the unit tests' dependencies, for checks.unit-tests.
            gradleUpdateTask = "assembleDebug testDebugUnitTest";
            restore = state;
          };
        in
        {
          packages = {
            inherit apk;
            default = apk;
            gradle-state = state;
          };

          # The APK itself is built by CI's `apk` job; checks cover what a
          # build does not: that the NDK runs here, and the JVM unit tests.
          checks = {
            ndk = android.ndkCheck { inherit sdk; };
            unit-tests = android.mkGradleBuild {
              name = "sivrad-unit-tests";
              inherit src sdk gradle prepare;
              gradleDeps = ./gradle-deps.json;
              mitmCache = apk.mitmCache;
              restore = state;
              buildPhase = ''
                runHook preBuild
                gradle testDebugUnitTest
                runHook postBuild
              '';
              installPhase = ''
                runHook preInstall
                mkdir -p $out
                for m in core/tools core/llm; do
                  mkdir -p $out/$m
                  cp -r $m/build/test-results $out/$m/ || true
                done
                runHook postInstall
              '';
            };
          };

          devShells.default = pkgs.mkShell {
            packages = [ gradle sdk pkgs.jdk17 ];
            ANDROID_HOME = "${sdk}/libexec/android-sdk";
            ANDROID_SDK_ROOT = "${sdk}/libexec/android-sdk";
            JAVA_HOME = "${pkgs.jdk17}";
          };
        };
    };
}

# Homebrew Formula for kairo-code.
#
# Ship via a tap (e.g. `brew tap captaingreenskin/kairo`) — drop this file in
# the tap repo's `Formula/` directory. Once a v0.2.0 release exists on GitHub
# with the fat jar attached, users run:
#
#   brew tap captaingreenskin/kairo
#   brew install kairo-code
#
# Update `url`, `sha256`, and `version` for each release.

class KairoCode < Formula
  desc "Same Models. Governable. — A Java Code Agent built on Kairo"
  homepage "https://github.com/captaingreenskin/kairo-code"
  url "https://github.com/captaingreenskin/kairo-code/releases/download/v0.2.0/kairo-code-cli-0.2.0.jar"
  sha256 "REPLACE_WITH_REAL_SHA256_AT_RELEASE_TIME"
  license "Apache-2.0"
  version "0.2.0"

  # JDK 21 is the cleanest choice — Temurin is what we test against in CI.
  # Users can use any JDK ≥ 17 by setting KAIRO_CODE_JAVA_HOME themselves.
  depends_on "openjdk@21"

  def install
    libexec.install "kairo-code-cli-0.2.0.jar" => "kairo-code.jar"
    (bin/"kairo-code").write <<~EOS
      #!/bin/sh
      # Resolve java: honor KAIRO_CODE_JAVA_HOME first, else use the
      # Homebrew-provisioned openjdk@21, else PATH.
      if [ -n "$KAIRO_CODE_JAVA_HOME" ]; then
        JAVA="$KAIRO_CODE_JAVA_HOME/bin/java"
      elif [ -x "#{Formula["openjdk@21"].opt_bin}/java" ]; then
        JAVA="#{Formula["openjdk@21"].opt_bin}/java"
      else
        JAVA="java"
      fi
      exec "$JAVA" -jar "#{libexec}/kairo-code.jar" "$@"
    EOS
    (bin/"kairo-code").chmod(0755)
  end

  test do
    # `--help` should exit 0 and print the usage banner without touching any
    # real API. Catches obvious packaging breaks.
    output = shell_output("#{bin}/kairo-code --help")
    assert_match "Same Models. Governable.", output
  end
end

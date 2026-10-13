package com.softitrend.office.infrastructure;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;
class CredentialConfigurationTest {
 @ParameterizedTest @ValueSource(strings={"", "plaintext-not-a-hash", "$2b$03$.Y2ZtUVqsvGorQWKEXT3S.iD/USZkKLPlnqbWmfdJkNXbm9bRV9T2", "$2b$99$.Y2ZtUVqsvGorQWKEXT3S.iD/USZkKLPlnqbWmfdJkNXbm9bRV9T2"})
 void invalidConfiguredHashCannotCreateAnOperator(String hash) {
  var env=new MockEnvironment().withProperty("office.operator.username","operator-test").withProperty("office.operator.password-hash",hash);
  assertThat(new SecurityConfiguration().users(env).findByUsername("operator-test").block()).isNull();
 }
}

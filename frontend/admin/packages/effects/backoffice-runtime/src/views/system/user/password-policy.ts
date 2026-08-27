export const LOCAL_PASSWORD_SPECIAL_CHARACTERS = '!@#$%^&*';

const LOWERCASE_CHARACTERS = 'abcdefghijklmnopqrstuvwxyz';
const UPPERCASE_CHARACTERS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ';
const DIGIT_CHARACTERS = '0123456789';
const ALPHANUMERIC_CHARACTERS = `${LOWERCASE_CHARACTERS}${UPPERCASE_CHARACTERS}${DIGIT_CHARACTERS}`;
const ALLOWED_CHARACTERS = `${ALPHANUMERIC_CHARACTERS}${LOCAL_PASSWORD_SPECIAL_CHARACTERS}`;

export type RandomIndex = (upperBound: number) => number;
export type RandomValueSource = (
  values: Uint32Array<ArrayBuffer>,
) => Uint32Array<ArrayBuffer>;

export function isLocalPasswordResetMode(environment: {
  explicitMode?: string;
  prod: boolean;
}) {
  if (environment.prod) return false;
  if (
    environment.explicitMode !== 'local' &&
    environment.explicitMode !== 'oidc'
  ) {
    throw new Error('Invalid local password reset authentication mode');
  }
  return environment.explicitMode === 'local';
}

export function generateLocalPassword(
  randomIndex: RandomIndex = secureRandomIndex,
) {
  const length = 16 + randomIndex(9);
  const characters = [
    pick(LOWERCASE_CHARACTERS, randomIndex),
    pick(UPPERCASE_CHARACTERS, randomIndex),
    pick(DIGIT_CHARACTERS, randomIndex),
    ...Array.from({ length: 4 }, () =>
      pick(LOCAL_PASSWORD_SPECIAL_CHARACTERS, randomIndex),
    ),
  ];
  while (characters.length < length) {
    characters.push(pick(ALPHANUMERIC_CHARACTERS, randomIndex));
  }
  for (let index = characters.length - 1; index > 0; index -= 1) {
    const replacement = randomIndex(index + 1);
    const currentCharacter = characters[index];
    const replacementCharacter = characters[replacement];
    if (currentCharacter === undefined || replacementCharacter === undefined) {
      throw new RangeError(
        'Random index is outside the password character set',
      );
    }
    characters[index] = replacementCharacter;
    characters[replacement] = currentCharacter;
  }
  return characters.join('');
}

export function isValidLocalPassword(password: string) {
  if (password.length < 16 || password.length > 24) return false;
  let specialCharacters = 0;
  let uppercase = false;
  let lowercase = false;
  let digit = false;
  for (const character of password) {
    if (!ALLOWED_CHARACTERS.includes(character)) return false;
    if (LOCAL_PASSWORD_SPECIAL_CHARACTERS.includes(character)) {
      specialCharacters += 1;
    } else if (LOWERCASE_CHARACTERS.includes(character)) {
      lowercase = true;
    } else if (UPPERCASE_CHARACTERS.includes(character)) {
      uppercase = true;
    } else if (DIGIT_CHARACTERS.includes(character)) {
      digit = true;
    }
  }
  return uppercase && lowercase && digit && specialCharacters === 4;
}

function pick(characters: string, randomIndex: RandomIndex) {
  const character = characters[randomIndex(characters.length)];
  if (character === undefined) {
    throw new RangeError('Random index is outside the password character set');
  }
  return character;
}

export function secureRandomIndex(
  upperBound: number,
  randomValues: RandomValueSource = (values) => {
    crypto.getRandomValues(values);
    return values;
  },
) {
  if (!Number.isSafeInteger(upperBound) || upperBound < 1) {
    throw new RangeError('upperBound must be a positive safe integer');
  }
  const rejectionLimit = Math.floor(0x1_00_00_00_00 / upperBound) * upperBound;
  const random = new Uint32Array(
    new ArrayBuffer(Uint32Array.BYTES_PER_ELEMENT),
  );
  let randomValue: number;
  do {
    randomValues(random);
    const sampledValue = random[0];
    if (sampledValue === undefined) {
      throw new Error('Secure random source returned no value');
    }
    randomValue = sampledValue;
  } while (randomValue >= rejectionLimit);
  return randomValue % upperBound;
}

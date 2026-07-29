interface LoginDefaults {
  password: string;
  username: string;
}

function resolveLoginDefaults(): LoginDefaults {
  return {
    password: '',
    username: '',
  };
}

export { resolveLoginDefaults };

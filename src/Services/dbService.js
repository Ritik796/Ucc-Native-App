import { get, ref, set, update } from "firebase/database";
import { getCurrentDatabase } from "../Firebase";

export const getData = (path,database) => {
  return new Promise(async (resolve) => {
    get(ref(database, path)).then((snapshot) => {
      let data = snapshot.val();
      resolve(data);
    });
  });
};

export const saveData = (path, data,database) => {
  return new Promise(async (resolve) => {
    update(ref(database, path), data);
    resolve("success");

  });
};

export const setData = (path, value,database) => {
  return new Promise(async (resolve) => {


    set(ref(database, path), value);
    resolve("success");

  });
};

export const RemoveData = (path,database) => {
  return new Promise(async (resolve) => {
    remove(ref(database, path));
    resolve("success");

  });
};

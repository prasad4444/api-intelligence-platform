import type { Row } from "./api";
export const HTTP_METHODS=["get","post","put","patch","delete","head","options","trace"];
export type Operation={key:string;method:string;path:string;operation:Row;pathItem:Row};
export const object=(value:unknown):Row=>value!==null&&typeof value==="object"&&!Array.isArray(value)?value as Row:{};
export function resolve(value:unknown,document:Row,seen=new Set<string>()):Row {
 const node=object(value);if(typeof node.$ref!=="string")return node;
 const ref=node.$ref;
 if(!ref.startsWith("#/"))return {...node,"x-preview-warning":"External reference: import a bundled OpenAPI document to inspect this definition."};
 if(seen.has(ref))return {...node,"x-preview-warning":"Recursive reference — inspect the referenced schema for its recursive fields."};
 let target:unknown=document;
 try{for(const part of decodeURIComponent(ref.slice(2)).split("/").map(p=>p.replace(/~1/g,"/").replace(/~0/g,"~"))){if(target===null||typeof target!=="object"||!Object.prototype.hasOwnProperty.call(target,part))throw Error();target=(target as Row)[part];}}
 catch{return {...node,"x-preview-warning":"Reference not found in this document."};}
 if(!target||typeof target!=="object"||Array.isArray(target))return {...node,"x-preview-warning":"Reference does not point to an object definition."};
 const next=new Set(seen);next.add(ref);const resolved=resolve(target,document,next);const {$ref,...siblings}=node;return {...resolved,...siblings,...(resolved["x-preview-warning"]?{$ref:ref}:{})};
}
export function listOperations(document?:Row):Operation[]{if(!document)return [];return Object.entries(object(document.paths)).flatMap(([path,value])=>{const pathItem=resolve(value,document);return HTTP_METHODS.filter(method=>pathItem[method]&&typeof pathItem[method]==="object").map(method=>({key:method.toUpperCase()+" "+path,method:method.toUpperCase(),path,operation:resolve(pathItem[method],document),pathItem}));});}
export function operationParameters(item:Operation,document:Row):Row[]{const params=new Map<string,Row>();for(const source of [item.pathItem.parameters,item.operation.parameters])if(Array.isArray(source))for(const value of source){const p=resolve(value,document);params.set(String(p.in)+":"+String(p.name||p.$ref),p);}return [...params.values()];}
export function schemaExample(input:unknown,document:Row,mode:"request"|"response",depth=0,refs=new Set<string>()):unknown {
 const raw=object(input);if(depth>6||typeof raw.$ref==="string"&&refs.has(raw.$ref))return null;
 const next=new Set(refs);if(raw.$ref)next.add(raw.$ref);const schema=resolve(raw,document);
 if(schema["x-preview-warning"])return null;
 if(schema.example!==undefined)return schema.example;if(Array.isArray(schema.examples)&&schema.examples.length)return schema.examples[0];if(schema.default!==undefined)return schema.default;if(Array.isArray(schema.enum)&&schema.enum.length)return schema.enum[0];
 if(Array.isArray(schema.allOf)){const result:Row=Object.create(null);for(const part of schema.allOf){const value=schemaExample(part,document,mode,depth+1,next);if(value&&typeof value==="object"&&!Array.isArray(value))for(const [k,v] of Object.entries(value))result[k]=v;}for(const [key,property] of Object.entries(object(schema.properties))){const p=resolve(property,document);if(mode==="request"&&p.readOnly||mode==="response"&&p.writeOnly)continue;result[key]=schemaExample(property,document,mode,depth+1,next);}return result;}
 const choice=schema.oneOf?.[0]||schema.anyOf?.[0];if(choice)return schemaExample(choice,document,mode,depth+1,next);
 const type=Array.isArray(schema.type)?schema.type.find(t=>t!=="null"):schema.type;
 if(type==="object"||schema.properties){const result:Row=Object.create(null);for(const [key,property]of Object.entries(object(schema.properties))){const p=resolve(property,document);if(mode==="request"&&p.readOnly||mode==="response"&&p.writeOnly)continue;result[key]=schemaExample(property,document,mode,depth+1,next);}return result;}
 if(type==="array")return [schemaExample(schema.items,document,mode,depth+1,next)];
 if(type==="integer"||type==="number")return typeof schema.minimum==="number"?schema.minimum:0;
 if(type==="boolean")return false;if(type==="null")return null;
 return schema.format==="date"?"2026-01-01":schema.format==="date-time"?"2026-01-01T00:00:00Z":schema.format==="uuid"?"00000000-0000-4000-8000-000000000000":schema.format==="email"?"user@example.com":"string";
}
export function mediaExamples(media:Row,document:Row,mode:"request"|"response"):{name:string;value:unknown;generated:boolean}[]{
 const out:{name:string;value:unknown;generated:boolean}[]=[];
 if(media.example!==undefined)out.push({name:"Example",value:media.example,generated:false});
 for(const [name,value]of Object.entries(object(media.examples))){const example=resolve(value,document);if(example.value!==undefined)out.push({name,value:example.value,generated:false});else if(example.externalValue)out.push({name,value:"External example: "+example.externalValue,generated:false});else if(example["x-preview-warning"])out.push({name,value:example["x-preview-warning"]+" "+(example.$ref||""),generated:false});}
 if(!out.length&&media.schema)out.push({name:"Generated schema example",value:schemaExample(media.schema,document,mode),generated:true});return out;
}
export function operationServer(item:Operation,document:Row):string{const server=(item.operation.servers||item.pathItem.servers||document.servers||[])[0];let url=typeof server?.url==="string"?server.url:"";for(const [key,v]of Object.entries(object(server?.variables)))url=url.replaceAll("{"+key+"}",String(object(v).default??"{"+key+"}"));return url;}

export function operationServers(item: Operation, document: Row) {
  const servers = item.operation.servers ?? item.pathItem.servers ?? document.servers;
  if (!Array.isArray(servers)) return [];
  return servers.map((value, index) => {
    const server = object(value);
    let url = typeof server.url === "string" ? server.url : "";
    for (const [name, variable] of Object.entries(object(server.variables))) {
      url = url.replaceAll("{" + name + "}", String(object(variable).default ?? "{" + name + "}"));
    }
    return { index, url, description: typeof server.description === "string" ? server.description : "" };
  });
}

export function explorerModel(document?: Row) {
  const operations = listOperations(document);
  const tags = new Map<string, Operation[]>();
  for (const item of operations) {
    const names = Array.isArray(item.operation.tags) ? item.operation.tags.filter((tag: unknown): tag is string => typeof tag === "string") : [];
    for (const tag of new Set<string>(names.length ? names : ["default"])) {
      const group = tags.get(tag) ?? [];
      group.push(item);
      tags.set(tag, group);
    }
  }
  return { operations, tags, schemas: Object.entries(object(document?.components?.schemas)) };
}

import {boundedJson,object,text,privateKey,signJwt} from './protocol';

/** Per-request OAuth session; service-account credentials and Play tokens never enter logs/storage. */
export class GooglePlay {
  private constructor(private readonly accessToken:string,private readonly packageName:string) {}
  static async connect(serviceAccountJson:string,packageName:string,now:number):Promise<GooglePlay> {
    const account=object(JSON.parse(serviceAccountJson));
    if(typeof account.private_key!=='string' || account.private_key.length>16_384) throw new Error('invalid_service_account');
    const assertion=await signJwt({iss:text(account.client_email),
      scope:'https://www.googleapis.com/auth/androidpublisher https://www.googleapis.com/auth/playintegrity',
      aud:'https://oauth2.googleapis.com/token',iat:now,exp:now+3600},await privateKey(account.private_key));
    const response=await fetch('https://oauth2.googleapis.com/token',{method:'POST',redirect:'error',signal:AbortSignal.timeout(15_000),
      headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({grant_type:'urn:ietf:params:oauth:grant-type:jwt-bearer',assertion})});
    if(!response.ok) {await response.body?.cancel();throw new Error('google_oauth_unavailable');}
    const result=object(await boundedJson(response));
    return new GooglePlay(text(result.access_token),packageName);
  }
  private async request(url:string,body?:unknown):Promise<Response> {
    return fetch(url,{method:body===undefined?'GET':'POST',redirect:'error',signal:AbortSignal.timeout(15_000),
      headers:{Authorization:`Bearer ${this.accessToken}`,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});
  }
  async integrity(token:string):Promise<unknown> {
    const response=await this.request(`https://playintegrity.googleapis.com/v1/${encodeURIComponent(this.packageName)}:decodeIntegrityToken`,{integrity_token:token});
    if(!response.ok) {await response.body?.cancel();throw new Error('google_integrity_unavailable');}
    return object(await boundedJson(response)).tokenPayloadExternal;
  }
  private purchaseUrl(productId:string,token:string):string {
    return `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(this.packageName)}/purchases/products/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(token)}`;
  }
  async purchase(productId:string,token:string):Promise<unknown> {
    const response=await this.request(this.purchaseUrl(productId,token));
    if(response.status===404 || response.status===410) {await response.body?.cancel();return {purchaseState:1};}
    if(!response.ok) {await response.body?.cancel();throw new Error('google_publisher_unavailable');}
    return boundedJson(response);
  }
  async acknowledge(productId:string,token:string):Promise<void> {
    const response=await this.request(this.purchaseUrl(productId,token)+':acknowledge',{});
    await response.body?.cancel();
    if(!response.ok) throw new Error('google_acknowledgement_unavailable');
  }
  async voided(startMillis:number|null,endMillis:number,cursor:string|null):Promise<unknown> {
    const url=new URL(`https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(this.packageName)}/purchases/voidedpurchases`);
    url.searchParams.set('type','0');url.searchParams.set('includeQuantityBasedPartialRefund','false');url.searchParams.set('maxResults','50');
    if(cursor!==null) url.searchParams.set('token',text(cursor));
    else {
      if(startMillis!==null) url.searchParams.set('startTime',String(startMillis));
      url.searchParams.set('endTime',String(endMillis));
    }
    const response=await this.request(url.toString());
    if(response.status===400 && cursor!==null) {await response.body?.cancel();throw new Error('google_voided_cursor_rejected');}
    if(!response.ok) {await response.body?.cancel();throw new Error('google_voided_unavailable');}
    return boundedJson(response,1024*1024);
  }
}
